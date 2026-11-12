plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.diffplug.spotless") version "8.11.0" apply false
}
subprojects {
    dependencyLocking { lockAllConfigurations() }
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.7.1")
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.7.1")
        }
    }
}
