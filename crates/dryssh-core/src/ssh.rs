//! SSH operates exclusively on a caller-supplied transport.

use std::{sync::Arc, time::Duration};

use russh::{
	ChannelMsg, client,
	keys::{HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate},
};
use serde::{Deserialize, Serialize};
use tokio::{
	io::{AsyncRead, AsyncWrite},
	sync::{mpsc, watch},
};
use tokio_util::sync::CancellationToken;

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Authentication {
	Password {
		password: String,
	},
	PrivateKey {
		key: String,
		passphrase: Option<String>,
	},
}

#[derive(Deserialize)]
pub struct ConnectOptions {
	pub username: String,
	pub authentication: Authentication,
	pub trusted_fingerprint: Option<String>,
	pub columns: u32,
	pub rows: u32,
}

pub enum Command {
	Input(Vec<u8>),
	Resize {
		columns: u32,
		rows: u32,
		width: u32,
		height: u32,
	},
}

#[derive(Debug, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ControlEvent {
	HostKey {
		algorithm: String,
		fingerprint: String,
	},
	Connected,
	Closed {
		reason: CloseReason,
		detail: String,
	},
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CloseReason {
	Disconnected,
	RemoteClosed,
	Network,
	Authentication,
	HostKey,
}

#[derive(Debug)]
pub enum Event {
	Data(Vec<u8>),
	Control(ControlEvent),
}

#[derive(Debug, thiserror::Error)]
enum Error {
	#[error("SSH connection failed: {0}")]
	Ssh(#[from] russh::Error),
	#[error("Cannot unlock private key: {0}")]
	Key(#[from] russh::keys::Error),
	#[error("Authentication rejected. Check the username and credentials.")]
	Authentication,
	#[error("Host key was rejected or has changed. Verify the server before reconnecting.")]
	HostKey,
	#[error("Server refused the terminal or shell request.")]
	Shell,
	#[error("Session closed.")]
	Closed,
}

struct Handler {
	trusted_fingerprint: Option<String>,
	events: mpsc::Sender<Event>,
	trust: watch::Receiver<Option<bool>>,
	cancel: CancellationToken,
}

impl client::Handler for Handler {
	type Error = Error;

	async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Error> {
		let key = key.public_key();
		let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
		if let Some(trusted) = &self.trusted_fingerprint {
			return if *trusted == fingerprint {
				Ok(true)
			} else {
				Err(Error::HostKey)
			};
		}
		self.events
			.send(Event::Control(ControlEvent::HostKey {
				algorithm: key.algorithm().to_string(),
				fingerprint,
			}))
			.await
			.map_err(|_| Error::Closed)?;
		tokio::select! {
			biased;
			_ = self.cancel.cancelled() => return Err(Error::Closed),
			result = self.trust.wait_for(|decision| decision.is_some()) => {
				result.map_err(|_| Error::Closed)?;
			}
		}
		if *self.trust.borrow_and_update() == Some(true) {
			Ok(true)
		} else {
			Err(Error::HostKey)
		}
	}
}

/// Dropping or cancelling the future also drops the supplied transport.
pub async fn run<S>(
	stream: S,
	options: ConnectOptions,
	mut commands: mpsc::Receiver<Command>,
	events: mpsc::Sender<Event>,
	trust: watch::Receiver<Option<bool>>,
	cancel: CancellationToken,
) where
	S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
	let _cancel_on_drop = cancel.clone().drop_guard();
	let (transport, mut relay) = tokio::io::duplex(64 * 1024);
	let relay_cancel = cancel.clone();
	let relay_task = tokio::spawn(async move {
		let mut stream = stream;
		tokio::select! {
			biased;
			_ = relay_cancel.cancelled() => {},
			_ = tokio::io::copy_bidirectional(&mut stream, &mut relay) => {},
		}
	});
	let result = tokio::select! {
		biased;
		_ = cancel.cancelled() => None,
		result = connected(transport, options, &mut commands, &events, trust, cancel.clone()) => Some(result),
	};
	cancel.cancel();
	let _ = relay_task.await;
	let (reason, detail) = match result {
		None => (CloseReason::Disconnected, "Disconnected".to_owned()),
		Some(Ok(())) => (
			CloseReason::RemoteClosed,
			"Server closed the shell".to_owned(),
		),
		Some(Err(error)) => {
			let reason = match error {
				Error::Authentication | Error::Key(_) => CloseReason::Authentication,
				Error::HostKey => CloseReason::HostKey,
				_ => CloseReason::Network,
			};
			(reason, error.to_string())
		}
	};
	let _ = events
		.send(Event::Control(ControlEvent::Closed { reason, detail }))
		.await;
}

async fn connected<S>(
	stream: S,
	options: ConnectOptions,
	commands: &mut mpsc::Receiver<Command>,
	events: &mpsc::Sender<Event>,
	trust: watch::Receiver<Option<bool>>,
	cancel: CancellationToken,
) -> Result<(), Error>
where
	S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
	let config = client::Config {
		keepalive_interval: Some(Duration::from_secs(15)),
		keepalive_max: 4,
		..Default::default()
	};
	let handler = Handler {
		trusted_fingerprint: options.trusted_fingerprint,
		events: events.clone(),
		trust,
		cancel,
	};
	let mut session = client::connect_stream(Arc::new(config), stream, handler).await?;
	let authenticated = match options.authentication {
		Authentication::Password { password } => {
			session
				.authenticate_password(&options.username, password)
				.await?
		}
		Authentication::PrivateKey { key, passphrase } => {
			let key = russh::keys::decode_secret_key(&key, passphrase.as_deref())?;
			let hash = session.best_supported_rsa_hash().await?.flatten();
			session
				.authenticate_publickey(
					&options.username,
					PrivateKeyWithHashAlg::new(Arc::new(key), hash),
				)
				.await?
		}
	};
	if !authenticated.success() {
		return Err(Error::Authentication);
	}
	let mut channel = session.channel_open_session().await?;
	channel
		.request_pty(
			true,
			"xterm-256color",
			options.columns,
			options.rows,
			0,
			0,
			&[],
		)
		.await?;
	request_accepted(&mut channel, events).await?;
	channel.request_shell(true).await?;
	request_accepted(&mut channel, events).await?;
	// Inputs queued during authentication must never reach the new shell.
	while commands.try_recv().is_ok() {}
	events
		.send(Event::Control(ControlEvent::Connected))
		.await
		.map_err(|_| Error::Closed)?;
	loop {
		tokio::select! {
			command = commands.recv() => match command {
				Some(Command::Input(data)) => channel.data_bytes(data).await?,
				Some(Command::Resize { columns, rows, width, height }) => channel.window_change(columns, rows, width, height).await?,
				None => break,
			},
			message = channel.wait() => match message {
				Some(ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. }) => {
					events.send(Event::Data(data.to_vec())).await.map_err(|_| Error::Closed)?;
				}
				Some(ChannelMsg::Close) | None => break,
				_ => {},
			}
		}
	}
	session
		.disconnect(russh::Disconnect::ByApplication, "", "")
		.await?;
	Ok(())
}

async fn request_accepted(
	channel: &mut russh::Channel<client::Msg>,
	events: &mpsc::Sender<Event>,
) -> Result<(), Error> {
	loop {
		match channel.wait().await {
			Some(ChannelMsg::Success) => return Ok(()),
			Some(ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. }) => {
				events
					.send(Event::Data(data.to_vec()))
					.await
					.map_err(|_| Error::Closed)?;
			}
			Some(ChannelMsg::Failure | ChannelMsg::Close) | None => return Err(Error::Shell),
			_ => {}
		}
	}
}
