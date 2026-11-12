//! Platform-independent SSH session policy and configuration.

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub mod policy;
pub mod ssh;

pub mod config;
