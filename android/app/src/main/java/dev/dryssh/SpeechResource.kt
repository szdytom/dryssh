package dev.dryssh

internal enum class SpeechResource(val label: String, val id: String) {
	SeedHourly("Doubao 2.0 · hourly", "volc.seedasr.sauc.duration"),
	SeedConcurrent("Doubao 2.0 · concurrent", "volc.seedasr.sauc.concurrent"),
	BigHourly("Doubao 1.0 · hourly", "volc.bigasr.sauc.duration"),
	BigConcurrent("Doubao 1.0 · concurrent", "volc.bigasr.sauc.concurrent");

	companion object {
		fun fromId(id: String) = entries.first { it.id == id }
	}
}
