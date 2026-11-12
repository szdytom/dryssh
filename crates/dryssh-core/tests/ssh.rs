use std::{
	sync::{
		Arc,
		atomic::{AtomicUsize, Ordering},
	},
	time::Duration,
};

use dryssh_core::ssh::{
	self, Authentication, CloseReason, Command, ConnectOptions, ControlEvent, Event,
};
use russh::{
	Channel, ChannelId, Pty,
	keys::{Algorithm, HashAlg, PrivateKey},
	server::{self, Session, VerifiedPublicKey},
};
use tokio::{
	sync::{mpsc, watch},
	time::timeout,
};
use tokio_util::sync::CancellationToken;

#[derive(Debug, PartialEq, Eq)]
enum Observed {
	Input(Vec<u8>),
	Size(u32, u32),
}

struct Server {
	observed: mpsc::UnboundedSender<Observed>,
	authentications: Arc<AtomicUsize>,
}

impl server::Handler for Server {
	type Error = russh::Error;

	async fn auth_password(
		&mut self,
		user: &str,
		password: &str,
	) -> Result<server::Auth, Self::Error> {
		self.authentications.fetch_add(1, Ordering::SeqCst);
		Ok(if user == "user" && password == "correct" {
			server::Auth::Accept
		} else {
			server::Auth::reject()
		})
	}

	async fn auth_publickey(
		&mut self,
		user: &str,
		_key: VerifiedPublicKey<'_>,
	) -> Result<server::Auth, Self::Error> {
		self.authentications.fetch_add(1, Ordering::SeqCst);
		Ok(if user == "user" {
			server::Auth::Accept
		} else {
			server::Auth::reject()
		})
	}

	async fn channel_open_session(
		&mut self,
		_channel: Channel<server::Msg>,
		reply: server::ChannelOpenHandle,
		_session: &mut Session,
	) -> Result<(), Self::Error> {
		reply.accept().await;
		Ok(())
	}

	async fn pty_request(
		&mut self,
		channel: ChannelId,
		term: &str,
		columns: u32,
		rows: u32,
		_width: u32,
		_height: u32,
		_modes: &[(Pty, u32)],
		session: &mut Session,
	) -> Result<(), Self::Error> {
		assert_eq!(term, "xterm-256color");
		self.observed.send(Observed::Size(columns, rows)).unwrap();
		session.channel_success(channel)?;
		Ok(())
	}

	async fn shell_request(
		&mut self,
		channel: ChannelId,
		session: &mut Session,
	) -> Result<(), Self::Error> {
		session.channel_success(channel)?;
		session.data(channel, "中文 shell ready\r\n".as_bytes())?;
		Ok(())
	}

	async fn window_change_request(
		&mut self,
		_channel: ChannelId,
		columns: u32,
		rows: u32,
		_width: u32,
		_height: u32,
		_session: &mut Session,
	) -> Result<(), Self::Error> {
		self.observed.send(Observed::Size(columns, rows)).unwrap();
		Ok(())
	}

	async fn data(
		&mut self,
		channel: ChannelId,
		data: &[u8],
		session: &mut Session,
	) -> Result<(), Self::Error> {
		self.observed.send(Observed::Input(data.to_vec())).unwrap();
		session.data(channel, data.to_vec())?;
		Ok(())
	}
}

enum Trust {
	Unknown,
	Pinned,
	Changed,
}

struct Fixture {
	commands: mpsc::Sender<Command>,
	events: mpsc::Receiver<Event>,
	trust: watch::Sender<Option<bool>>,
	cancel: CancellationToken,
	observed: mpsc::UnboundedReceiver<Observed>,
	authentications: Arc<AtomicUsize>,
	task: tokio::task::JoinHandle<()>,
	server_task: tokio::task::JoinHandle<()>,
	fingerprint: String,
}

impl Fixture {
	async fn new(authentication: Authentication, trusted: Trust) -> Self {
		let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
		let fingerprint = key.public_key().fingerprint(HashAlg::Sha256).to_string();
		let config = Arc::new(server::Config {
			keys: vec![key],
			auth_rejection_time: Duration::ZERO,
			..Default::default()
		});
		let (stream, peer) = tokio::io::duplex(64 * 1024);
		let (observed_tx, observed) = mpsc::unbounded_channel();
		let authentications = Arc::new(AtomicUsize::new(0));
		let server = Server {
			observed: observed_tx,
			authentications: authentications.clone(),
		};
		let server_task = tokio::spawn(async move {
			if let Ok(session) = server::run_stream(config, peer, server).await {
				let _ = session.await;
			}
		});
		let (commands, receiver) = mpsc::channel(32);
		let (sender, events) = mpsc::channel(32);
		let (trust, trust_rx) = watch::channel(None);
		let cancel = CancellationToken::new();
		let task = tokio::spawn(ssh::run(
			stream,
			ConnectOptions {
				username: "user".into(),
				authentication,
				trusted_fingerprint: match trusted {
					Trust::Unknown => None,
					Trust::Pinned => Some(fingerprint.clone()),
					Trust::Changed => Some("SHA256:wrong".into()),
				},
				columns: 80,
				rows: 24,
			},
			receiver,
			sender,
			trust_rx,
			cancel.clone(),
		));
		Self {
			commands,
			events,
			trust,
			cancel,
			observed,
			authentications,
			task,
			server_task,
			fingerprint,
		}
	}

	async fn event(&mut self) -> Event {
		timeout(Duration::from_secs(5), self.events.recv())
			.await
			.unwrap()
			.unwrap()
	}

	async fn accept(&mut self) {
		assert!(
			matches!(self.event().await, Event::Control(ControlEvent::HostKey { fingerprint, .. }) if fingerprint == self.fingerprint)
		);
		assert_eq!(self.authentications.load(Ordering::SeqCst), 0);
		self.trust.send(Some(true)).unwrap();
		assert!(matches!(
			self.event().await,
			Event::Control(ControlEvent::Connected)
		));
	}

	async fn stop(self) {
		self.cancel.cancel();
		timeout(Duration::from_secs(2), self.task)
			.await
			.unwrap()
			.unwrap();
		timeout(Duration::from_secs(2), self.server_task)
			.await
			.unwrap()
			.unwrap();
	}
}

fn password(value: &str) -> Authentication {
	Authentication::Password {
		password: value.into(),
	}
}

#[tokio::test]
async fn password_shell_utf8_pty_resize_and_no_pre_auth_input_replay() {
	let mut f = Fixture::new(password("correct"), Trust::Unknown).await;
	f.commands
		.send(Command::Input(b"must not run".to_vec()))
		.await
		.unwrap();
	f.accept().await;
	assert!(
		matches!(f.event().await, Event::Data(data) if data == "中文 shell ready\r\n".as_bytes())
	);
	assert_eq!(f.observed.recv().await, Some(Observed::Size(80, 24)));
	f.commands
		.send(Command::Resize {
			columns: 101,
			rows: 37,
			width: 0,
			height: 0,
		})
		.await
		.unwrap();
	assert_eq!(f.observed.recv().await, Some(Observed::Size(101, 37)));
	f.commands
		.send(Command::Input("输入\u{18}".as_bytes().to_vec()))
		.await
		.unwrap();
	assert!(matches!(f.event().await, Event::Data(data) if data == "输入\u{18}".as_bytes()));
	assert_eq!(
		f.observed.recv().await,
		Some(Observed::Input("输入\u{18}".as_bytes().to_vec()))
	);
	f.stop().await;
}

#[tokio::test]
async fn changed_key_never_attempts_authentication() {
	let mut f = Fixture::new(password("correct"), Trust::Changed).await;
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::Closed {
			reason: CloseReason::HostKey,
			..
		})
	));
	assert_eq!(f.authentications.load(Ordering::SeqCst), 0);
	f.stop().await;
}

#[tokio::test]
async fn cancel_while_awaiting_host_trust_closes_original_transport() {
	let mut f = Fixture::new(password("correct"), Trust::Unknown).await;
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::HostKey { .. })
	));
	f.stop().await;
}

#[tokio::test]
async fn authentication_failure_has_distinct_reason() {
	let mut f = Fixture::new(password("incorrect"), Trust::Unknown).await;
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::HostKey { .. })
	));
	f.trust.send(Some(true)).unwrap();
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::Closed {
			reason: CloseReason::Authentication,
			..
		})
	));
	f.stop().await;
}

#[tokio::test]
async fn imports_encrypted_and_unencrypted_openssh_keys() {
	let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
	for encrypted in [false, true] {
		let key = if encrypted {
			key.encrypt(&mut rand::rng(), "phrase").unwrap()
		} else {
			key.clone()
		};
		let authentication = Authentication::PrivateKey {
			key: key.to_openssh(Default::default()).unwrap().to_string(),
			passphrase: encrypted.then(|| "phrase".into()),
		};
		let mut f = Fixture::new(authentication, Trust::Unknown).await;
		f.accept().await;
		f.stop().await;
	}
}

#[tokio::test]
async fn trusted_key_skips_prompt_and_rejected_key_never_authenticates() {
	let mut known = Fixture::new(password("correct"), Trust::Pinned).await;
	assert!(matches!(
		known.event().await,
		Event::Control(ControlEvent::Connected)
	));
	known.stop().await;
	let mut rejected = Fixture::new(password("correct"), Trust::Unknown).await;
	assert!(matches!(
		rejected.event().await,
		Event::Control(ControlEvent::HostKey { .. })
	));
	rejected.trust.send(Some(false)).unwrap();
	assert!(matches!(
		rejected.event().await,
		Event::Control(ControlEvent::Closed {
			reason: CloseReason::HostKey,
			..
		})
	));
	assert_eq!(rejected.authentications.load(Ordering::SeqCst), 0);
	rejected.stop().await;
}

#[tokio::test]
async fn wrong_private_key_passphrase_has_authentication_reason() {
	let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
	let encrypted = key.encrypt(&mut rand::rng(), "correct phrase").unwrap();
	let auth = Authentication::PrivateKey {
		key: encrypted
			.to_openssh(Default::default())
			.unwrap()
			.to_string(),
		passphrase: Some("wrong phrase".into()),
	};
	let mut f = Fixture::new(auth, Trust::Pinned).await;
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::Closed {
			reason: CloseReason::Authentication,
			..
		})
	));
	f.stop().await;
}

#[tokio::test]
async fn dropping_driver_while_awaiting_trust_closes_transport() {
	let mut f = Fixture::new(password("correct"), Trust::Unknown).await;
	assert!(matches!(
		f.event().await,
		Event::Control(ControlEvent::HostKey { .. })
	));
	f.task.abort();
	assert!(f.task.await.unwrap_err().is_cancelled());
	timeout(Duration::from_secs(2), f.server_task)
		.await
		.unwrap()
		.unwrap();
}
