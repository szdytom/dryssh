//! Persisted configuration contains credential references, never credentials.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

#[derive(Default, Clone, Copy, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuthenticationMode {
	#[default]
	Password,
	PrivateKey,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
	pub id: String,
	pub name: String,
	pub host: String,
	pub port: u16,
	pub username: String,
	pub authentication: AuthenticationMode,
	pub credential_ref: String,
	#[serde(default = "enabled")]
	pub auto_reconnect: bool,
	#[serde(default)]
	pub wlan_only: bool,
}

fn enabled() -> bool {
	true
}

#[derive(Default, Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(default)]
pub struct Modifiers {
	pub ctrl: bool,
	pub alt: bool,
	pub shift: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Action {
	Control { character: String },
	Key { key: String },
	Text { text: String },
	Editor,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Binding {
	pub key: String,
	pub modifiers: Modifiers,
	pub action: Action,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct QuickAction {
	pub name: String,
	pub action: Action,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Settings {
	pub theme: Option<String>,
	pub foreground: u32,
	pub background: u32,
	pub ansi: [u32; 16],
	pub font_ref: Option<String>,
	pub font_size: f32,
	pub bindings: Vec<Binding>,
	pub quick_actions: Vec<QuickAction>,
}

impl Default for Settings {
	fn default() -> Self {
		Self {
			theme: None,
			foreground: 0xffd4dfe9,
			background: 0xff101a25,
			ansi: [
				0xff101a25, 0xffdb7777, 0xff97c592, 0xffdec68b, 0xff82a9d9, 0xffba9dd3, 0xff8ac6db,
				0xffd4dfe9, 0xff677888, 0xffff9999, 0xffb4e5ad, 0xfff5dfad, 0xffa7c9ef, 0xffd6bcf0,
				0xffafe4ef, 0xfff0f5fa,
			],
			font_ref: None,
			font_size: 14.0,
			bindings: vec![Binding {
				key: "e".into(),
				modifiers: Modifiers {
					ctrl: true,
					shift: true,
					alt: false,
				},
				action: Action::Editor,
			}],
			quick_actions: vec![
				QuickAction {
					name: "Ctrl-B".into(),
					action: Action::Control {
						character: "b".into(),
					},
				},
				QuickAction {
					name: "Ctrl-X".into(),
					action: Action::Control {
						character: "x".into(),
					},
				},
				QuickAction {
					name: "Editor".into(),
					action: Action::Editor,
				},
			],
		}
	}
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Configuration {
	pub version: u32,
	pub profiles: Vec<Profile>,
	pub settings: Settings,
}

impl Default for Configuration {
	fn default() -> Self {
		Self {
			version: 1,
			profiles: Vec::new(),
			settings: Settings::default(),
		}
	}
}

fn valid_reference(value: &str) -> bool {
	!value.is_empty()
		&& value
			.bytes()
			.all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

fn valid_key(key: &str) -> bool {
	(key.len() == 1
		&& (key.as_bytes()[0].is_ascii_lowercase() || key.as_bytes()[0].is_ascii_digit()))
		|| matches!(
			key,
			"space"
				| "escape" | "tab"
				| "enter" | "backspace"
				| "delete" | "insert"
				| "home" | "end"
				| "up" | "down"
				| "left" | "right"
				| "page_up" | "page_down"
		) || (1..=12).any(|number| key == format!("f{number}"))
}

impl Action {
	fn valid(&self) -> bool {
		match self {
			Self::Control { character } => {
				character.len() == 1 && character.as_bytes()[0].is_ascii_graphic()
			}
			Self::Key { key } => valid_key(key),
			Self::Text { text } => !text.is_empty(),
			Self::Editor => true,
		}
	}
}

impl Configuration {
	pub fn validate(&self) -> Result<(), &'static str> {
		if self.version != 1 {
			return Err("Unsupported configuration version");
		}
		let mut ids = HashSet::new();
		for profile in &self.profiles {
			if !valid_reference(&profile.id) || !ids.insert(&profile.id) {
				return Err("Profile IDs must be unique");
			}
			if profile.name.trim().is_empty()
				|| profile.host.trim().is_empty()
				|| profile.username.trim().is_empty()
				|| profile.port == 0
			{
				return Err("Name, host, username and a valid port are required");
			}
			if !valid_reference(&profile.credential_ref) {
				return Err("Invalid credential reference");
			}
		}
		let settings = &self.settings;
		if settings
			.theme
			.as_deref()
			.is_some_and(|id| !valid_reference(id))
		{
			return Err("Invalid theme reference");
		}
		if !(6.0..=40.0).contains(&settings.font_size) {
			return Err("Font size must be between 6 and 40");
		}
		if settings
			.font_ref
			.as_deref()
			.is_some_and(|f| !valid_reference(f))
		{
			return Err("Invalid font reference");
		}
		let mut shortcuts = HashSet::new();
		for binding in &settings.bindings {
			if !valid_key(&binding.key) || !binding.action.valid() {
				return Err("Invalid keyboard mapping");
			}
			if !shortcuts.insert((&binding.key, &binding.modifiers)) {
				return Err("A shortcut can have only one action");
			}
		}
		if settings
			.quick_actions
			.iter()
			.any(|a| a.name.trim().is_empty() || !a.action.valid())
		{
			return Err("Quick actions require a name and a valid action");
		}
		Ok(())
	}
}

#[cfg(test)]
mod tests {
	use super::*;
	use proptest::prelude::*;

	#[test]
	fn defaults_validate_and_unknown_secret_fields_are_rejected() {
		let config: Configuration = serde_json::from_str("{}").unwrap();
		config.validate().unwrap();
		let profile = r#"{"id":"p1","name":"server","host":"localhost","port":22,"username":"user","authentication":"password","credential_ref":"secret1","password":"never persist this"}"#;
		assert!(serde_json::from_str::<Profile>(profile).is_err());
		let mut config = config;
		config.settings.font_ref = Some("../font".into());
		assert!(config.validate().is_err());
	}

	#[test]
	fn legacy_colors_survive_and_theme_references_roundtrip() {
		let mut config: Configuration = serde_json::from_str(
			r#"{"settings":{"foreground":4278256131,"background":4278453510}}"#,
		)
		.unwrap();
		assert!(config.settings.theme.is_none());
		assert_eq!(config.settings.foreground, 0xff010203);
		assert_eq!(config.settings.background, 0xff040506);
		config.settings.theme = Some("everforest_dark_medium".into());
		config.validate().unwrap();
		let restored: Configuration =
			serde_json::from_str(&serde_json::to_string(&config).unwrap()).unwrap();
		assert_eq!(
			restored.settings.theme.as_deref(),
			Some("everforest_dark_medium")
		);
		config.settings.theme = Some("../theme".into());
		assert!(config.validate().is_err());
	}

	#[test]
	fn function_keys_use_portable_canonical_names() {
		assert!(valid_key("f1"));
		assert!(valid_key("f12"));
		for key in ["f0", "f13", "f01", "F1"] {
			assert!(!valid_key(key));
		}
	}

	#[test]
	fn rejects_duplicate_shortcuts_and_invalid_font_sizes() {
		let mut config = Configuration::default();
		config
			.settings
			.bindings
			.push(config.settings.bindings[0].clone());
		assert!(config.validate().is_err());
		config.settings.bindings.pop();
		config.settings.font_size = 0.0;
		assert!(config.validate().is_err());
	}

	proptest! {
		#[test]
		fn complete_argb_palette_roundtrips(colors in any::<[u32; 16]>()) {
			let mut config = Configuration::default();
			config.settings.ansi = colors;
			let json = serde_json::to_string(&config).unwrap();
			let restored: Configuration = serde_json::from_str(&json).unwrap();
			prop_assert_eq!(restored.settings.ansi, colors);
			prop_assert!(restored.validate().is_ok());
		}
	}
}
