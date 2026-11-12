use std::{
	collections::HashMap,
	net::TcpStream,
	os::fd::FromRawFd,
	sync::{
		Arc, Mutex, OnceLock,
		atomic::{AtomicBool, AtomicI64, Ordering},
	},
};

use dryssh_core::ssh::{self, Command, ControlEvent, Event};
use jni::{
	Env, NativeMethod,
	errors::{Error, Result},
	native_method,
	objects::{JByteArray, JObject, JString},
	sys::{jboolean, jint, jlong},
};
use tokio::{
	runtime::Runtime,
	sync::{mpsc, watch},
};
use tokio_util::sync::CancellationToken;

struct Endpoint {
	commands: mpsc::Sender<Command>,
	events: Mutex<mpsc::Receiver<Event>>,
	trust: watch::Sender<Option<bool>>,
	cancel: CancellationToken,
	connected: AtomicBool,
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Endpoint>>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn sessions() -> &'static Mutex<HashMap<i64, Arc<Endpoint>>> {
	SESSIONS.get_or_init(Mutex::default)
}

fn endpoint(id: i64) -> Option<Arc<Endpoint>> {
	sessions().lock().unwrap().get(&id).cloned()
}

fn exception(env: &mut Env<'_>, message: &str) -> Error {
	let _ = env.throw_new(
		jni::jni_str!("java/lang/IllegalStateException"),
		jni::strings::JNIString::from(message),
	);
	Error::JavaException
}

macro_rules! export {
    ($($signature:tt)*) => {
        #[allow(deprecated, reason = "jni ABI check uses AtomicBool::fetch_update")]
        const _: NativeMethod = native_method! {
            java_type = "dev.dryssh.NativeBridge",
            $($signature)*
        };
    };
}

export!(extern fn version() -> JString,);
fn version<'local>(env: &mut Env<'local>, _this: JObject<'local>) -> Result<JString<'local>> {
	env.new_string(dryssh_core::VERSION)
}

export!(extern fn start(fd: jint, options: JString) -> jlong,);
fn start(env: &mut Env<'_>, _this: JObject<'_>, fd: jint, options: JString<'_>) -> Result<jlong> {
	// SAFETY: Android transfers a connected, exclusively owned detached socket FD.
	let socket = unsafe { TcpStream::from_raw_fd(fd) };
	let options: ssh::ConnectOptions = serde_json::from_str(&options.try_to_string(env)?)
		.map_err(|_| exception(env, "Invalid SSH connection options"))?;
	socket
		.set_nonblocking(true)
		.map_err(|e| exception(env, &e.to_string()))?;
	let runtime = RUNTIME.get_or_init(|| {
		tokio::runtime::Builder::new_multi_thread()
			.worker_threads(2)
			.enable_all()
			.build()
			.expect("Tokio runtime creation")
	});
	let stream = {
		let _entered = runtime.enter();
		tokio::net::TcpStream::from_std(socket).map_err(|e| exception(env, &e.to_string()))?
	};
	let (commands, receiver) = mpsc::channel(64);
	let (sender, events) = mpsc::channel(64);
	let (trust, trust_receiver) = watch::channel(None);
	let cancel = CancellationToken::new();
	let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
	let endpoint = Arc::new(Endpoint {
		commands,
		events: Mutex::new(events),
		trust,
		cancel: cancel.clone(),
		connected: AtomicBool::new(false),
	});
	sessions().lock().unwrap().insert(id, endpoint);
	runtime.spawn(ssh::run(
		stream,
		options,
		receiver,
		sender,
		trust_receiver,
		cancel,
	));
	Ok(id)
}

export!(extern fn next_event(id: jlong) -> [jbyte],);
fn next_event<'local>(
	env: &mut Env<'local>,
	_this: JObject<'local>,
	id: jlong,
) -> Result<JByteArray<'local>> {
	let Some(endpoint) = endpoint(id) else {
		return Ok(JByteArray::null());
	};
	let event = endpoint.events.lock().unwrap().blocking_recv();
	let bytes = match event {
		Some(Event::Data(data)) => {
			let mut frame = Vec::with_capacity(data.len() + 1);
			frame.push(0);
			frame.extend(data);
			frame
		}
		Some(Event::Control(control)) => {
			match &control {
				ControlEvent::Connected => endpoint.connected.store(true, Ordering::Release),
				ControlEvent::Closed { .. } => endpoint.connected.store(false, Ordering::Release),
				_ => {}
			}
			let mut frame = vec![1];
			frame.extend(serde_json::to_vec(&control).expect("control events serialize"));
			frame
		}
		None => return Ok(JByteArray::null()),
	};
	env.byte_array_from_slice(&bytes)
}

export!(extern fn send_input(id: jlong, bytes: [jbyte]) -> jboolean,);
fn send_input(
	env: &mut Env<'_>,
	_this: JObject<'_>,
	id: jlong,
	bytes: JByteArray<'_>,
) -> Result<jboolean> {
	let Some(endpoint) = endpoint(id).filter(|e| e.connected.load(Ordering::Acquire)) else {
		return Ok(false);
	};
	let bytes = env.convert_byte_array(&bytes)?;
	Ok(endpoint
		.commands
		.blocking_send(Command::Input(bytes))
		.is_ok())
}

export!(extern fn resize(id: jlong, columns: jint, rows: jint, width: jint, height: jint) -> jboolean,);
fn resize(
	_env: &mut Env<'_>,
	_this: JObject<'_>,
	id: jlong,
	columns: jint,
	rows: jint,
	width: jint,
	height: jint,
) -> Result<jboolean> {
	let Some(endpoint) = endpoint(id) else {
		return Ok(false);
	};
	Ok(endpoint
		.commands
		.blocking_send(Command::Resize {
			columns: columns as u32,
			rows: rows as u32,
			width: width as u32,
			height: height as u32,
		})
		.is_ok())
}

export!(extern fn trust(id: jlong, accepted: jboolean),);
fn trust(_env: &mut Env<'_>, _this: JObject<'_>, id: jlong, accepted: jboolean) -> Result<()> {
	if let Some(endpoint) = endpoint(id) {
		let _ = endpoint.trust.send(Some(accepted));
	}
	Ok(())
}

export!(extern fn close(id: jlong),);
fn close(_env: &mut Env<'_>, _this: JObject<'_>, id: jlong) -> Result<()> {
	if let Some(endpoint) = sessions().lock().unwrap().remove(&id) {
		endpoint.connected.store(false, Ordering::Release);
		endpoint.cancel.cancel();
	}
	Ok(())
}

export!(extern fn transition(policy: JString, event: JString) -> JString,);
fn transition<'local>(
	env: &mut Env<'local>,
	_this: JObject<'local>,
	policy: JString<'local>,
	event: JString<'local>,
) -> Result<JString<'local>> {
	let policy_json = policy.try_to_string(env)?;
	let mut policy: dryssh_core::policy::Policy = if policy_json.is_empty() {
		Default::default()
	} else {
		serde_json::from_str(&policy_json)
			.map_err(|_| exception(env, "Invalid connection policy"))?
	};
	let event = serde_json::from_str(&event.try_to_string(env)?)
		.map_err(|_| exception(env, "Invalid connection event"))?;
	let effects = policy.apply(event);
	let result = serde_json::json!({ "state": policy.state(), "generation": policy.generation(), "effects": effects, "policy": policy });
	env.new_string(result.to_string())
}

export!(extern fn normalize_config(config: JString) -> JString,);
fn normalize_config<'local>(
	env: &mut Env<'local>,
	_this: JObject<'local>,
	config: JString<'local>,
) -> Result<JString<'local>> {
	let config: dryssh_core::config::Configuration =
		serde_json::from_str(&config.try_to_string(env)?)
			.map_err(|e| exception(env, &format!("Invalid configuration: {e}")))?;
	config.validate().map_err(|e| exception(env, e))?;
	env.new_string(serde_json::to_string(&config).unwrap())
}
