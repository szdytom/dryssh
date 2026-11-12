plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}
android {
	namespace = "dev.dryssh"
	compileSdk = 37
	defaultConfig {
		applicationId = "dev.dryssh"
		minSdk = 30
		targetSdk = 37
		versionCode = 1
		versionName = "0.1.0"
		ndk {
			abiFilters +=
				providers.gradleProperty("dryssh.abis").orElse("arm64-v8a,x86_64").get().split(",")
		}
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	buildTypes {
		release { signingConfig = signingConfigs.getByName("debug") }
	}
	sourceSets["main"].jniLibs.directories.add("../../target/android/jniLibs")
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	buildFeatures { compose = true }
}
dependencies {
	implementation(project(":terminal"))
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.13.0")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	androidTestImplementation("androidx.test:runner:1.7.0")
	androidTestImplementation("androidx.test:core:1.7.0")
	androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
