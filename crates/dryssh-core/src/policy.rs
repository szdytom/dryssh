//! Connection intent survives UI changes; transport attempts have distinct generations.

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Network {
	pub online: bool,
	pub wlan: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Options {
	pub auto_reconnect: bool,
	pub wlan_only: bool,
}

impl Default for Options {
	fn default() -> Self {
		Self {
			auto_reconnect: true,
			wlan_only: false,
		}
	}
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
	Idle,
	WaitingNetwork,
	WaitingWlan,
	Connecting,
	Connected,
	RetryPending,
	AuthenticationFailed,
	HostKeyFailed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Failure {
	Network,
	Authentication,
	HostKey,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
	Connect(Options),
	Disconnect,
	NetworkChanged(Network),
	Connected(u64),
	Failed(u64, Failure),
	Retry(u64),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Effect {
	CloseTransport,
	Connect { generation: u64, wlan_only: bool },
	ScheduleRetry { generation: u64 },
}

#[derive(Debug)]
pub struct Policy {
	state: State,
	generation: u64,
	options: Option<Options>,
	network: Network,
}

impl Default for Policy {
	fn default() -> Self {
		Self {
			state: State::Idle,
			generation: 0,
			options: None,
			network: Network::default(),
		}
	}
}

impl Policy {
	pub fn state(&self) -> State {
		self.state
	}

	pub fn generation(&self) -> u64 {
		self.generation
	}

	fn available(&self, options: Options) -> bool {
		if options.wlan_only {
			self.network.wlan
		} else {
			self.network.online
		}
	}

	fn begin(&mut self, effects: &mut Vec<Effect>) {
		let options = self.options.expect("connection intent exists");
		if self.available(options) {
			self.generation += 1;
			self.state = State::Connecting;
			effects.push(Effect::Connect {
				generation: self.generation,
				wlan_only: options.wlan_only,
			});
		} else {
			self.state = if options.wlan_only {
				State::WaitingWlan
			} else {
				State::WaitingNetwork
			};
		}
	}

	pub fn apply(&mut self, event: Event) -> Vec<Effect> {
		let mut effects = Vec::new();
		match event {
			Event::Connect(options) => {
				self.generation += 1;
				effects.push(Effect::CloseTransport);
				self.options = Some(options);
				self.begin(&mut effects);
			}
			Event::Disconnect => {
				self.generation += 1;
				self.options = None;
				self.state = State::Idle;
				effects.push(Effect::CloseTransport);
			}
			Event::NetworkChanged(network) => {
				self.network = network;
				if let Some(options) = self.options {
					if !self.available(options)
						&& matches!(
							self.state,
							State::Connecting | State::Connected | State::RetryPending
						) {
						self.generation += 1;
						effects.push(Effect::CloseTransport);
						if options.auto_reconnect {
							self.begin(&mut effects);
						} else {
							self.options = None;
							self.state = State::Idle;
						}
					} else if matches!(self.state, State::WaitingNetwork | State::WaitingWlan) {
						self.begin(&mut effects);
					}
				}
			}
			Event::Connected(generation)
				if generation == self.generation && self.state == State::Connecting =>
			{
				self.state = State::Connected;
			}
			Event::Failed(generation, failure)
				if generation == self.generation
					&& matches!(self.state, State::Connecting | State::Connected) =>
			{
				self.generation += 1;
				effects.push(Effect::CloseTransport);
				match failure {
					Failure::Authentication => {
						self.state = State::AuthenticationFailed;
						self.options = None;
					}
					Failure::HostKey => {
						self.state = State::HostKeyFailed;
						self.options = None;
					}
					Failure::Network => {
						if let Some(options) = self.options.filter(|o| o.auto_reconnect) {
							if self.available(options) {
								self.state = State::RetryPending;
								effects.push(Effect::ScheduleRetry {
									generation: self.generation,
								});
							} else {
								self.begin(&mut effects);
							}
						} else {
							self.state = State::Idle;
							self.options = None;
						}
					}
				}
			}
			Event::Retry(generation)
				if generation == self.generation && self.state == State::RetryPending =>
			{
				self.begin(&mut effects);
			}
			_ => {}
		}
		effects
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use proptest::prelude::*;

	const STRICT: Options = Options {
		auto_reconnect: true,
		wlan_only: true,
	};
	const ONLINE: Network = Network {
		online: true,
		wlan: true,
	};

	fn started(options: Options) -> Policy {
		let mut p = Policy::default();
		p.apply(Event::NetworkChanged(ONLINE));
		p.apply(Event::Connect(options));
		p
	}

	#[test]
	fn cellular_cannot_resume_wlan_connection() {
		let mut p = started(STRICT);
		p.apply(Event::Connected(p.generation()));
		assert_eq!(
			p.apply(Event::NetworkChanged(Network {
				online: true,
				wlan: false
			})),
			vec![Effect::CloseTransport]
		);
		assert_eq!(p.state(), State::WaitingWlan);
		assert!(p.apply(Event::Retry(p.generation())).is_empty());
		assert!(matches!(
			p.apply(Event::NetworkChanged(ONLINE)).as_slice(),
			[Effect::Connect {
				wlan_only: true,
				..
			}]
		));
	}

	#[test]
	fn disconnect_invalidates_retries_and_transport_callbacks() {
		let mut p = started(STRICT);
		let old = p.generation();
		p.apply(Event::Failed(old, Failure::Network));
		let retry = p.generation();
		p.apply(Event::Disconnect);
		for event in [
			Event::Retry(retry),
			Event::Connected(old),
			Event::NetworkChanged(ONLINE),
		] {
			assert!(p.apply(event).is_empty());
			assert_eq!(p.state(), State::Idle);
		}
	}

	#[test]
	fn security_errors_block_even_after_network_changes() {
		for failure in [Failure::Authentication, Failure::HostKey] {
			let mut p = started(STRICT);
			p.apply(Event::Failed(p.generation(), failure));
			let state = p.state();
			p.apply(Event::NetworkChanged(Network::default()));
			p.apply(Event::NetworkChanged(ONLINE));
			p.apply(Event::Retry(p.generation()));
			assert_eq!(p.state(), state);
		}
	}

	#[test]
	fn manual_wait_can_start_once_without_enabling_reconnect() {
		let mut p = Policy::default();
		p.apply(Event::Connect(Options {
			auto_reconnect: false,
			wlan_only: true,
		}));
		assert_eq!(p.state(), State::WaitingWlan);
		assert!(matches!(
			p.apply(Event::NetworkChanged(ONLINE)).as_slice(),
			[Effect::Connect { .. }]
		));
		p.apply(Event::Failed(p.generation(), Failure::Network));
		assert_eq!(p.state(), State::Idle);
		assert!(p.apply(Event::NetworkChanged(ONLINE)).is_empty());
	}

	proptest! {
		#[test]
		fn strict_never_connects_without_wlan(events in prop::collection::vec(any::<u8>(), 0..300)) {
			let mut p = Policy::default();
			let mut wlan = false;
			for value in events {
				let event = match value % 7 {
					0 => Event::Connect(STRICT),
					1 => Event::Disconnect,
					2 | 3 => {
						wlan = value % 7 == 2;
						Event::NetworkChanged(Network { online: true, wlan })
					}
					4 => Event::Failed(p.generation(), Failure::Network),
					5 => Event::Retry(p.generation()),
					_ => Event::Connected(p.generation()),
				};
				for effect in p.apply(event) {
					if let Effect::Connect { wlan_only, .. } = effect {
						prop_assert!(wlan);
						prop_assert!(wlan_only);
					}
				}
				if matches!(p.state(), State::Connecting | State::Connected) {
					prop_assert!(wlan);
				}
			}
		}
	}
}
