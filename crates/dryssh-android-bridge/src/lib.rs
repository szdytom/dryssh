use jni::{EnvUnowned, objects::JClass, sys::jstring};

// SAFETY: The symbol and ABI match `NativeBridge.version`; JNI owns the arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_dryssh_NativeBridge_version(
	mut unowned: EnvUnowned,
	_class: JClass,
) -> jstring {
	unowned
		.with_env(|env| -> jni::errors::Result<_> {
			Ok(env.new_string(dryssh_core::VERSION)?.into_raw())
		})
		.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
