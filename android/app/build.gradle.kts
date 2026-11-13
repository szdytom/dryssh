plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}
val licenseAssets = tasks.register<Sync>("licenseAssets") {
	into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
	from(rootProject.file("../LICENSE"))
	from(rootProject.file("../NOTICE.md"))
	from(rootProject.file("terminal/LICENSE")) { rename { "termlib-LICENSE" } }
	from(rootProject.file("terminal/src/main/cpp/libvterm/LICENSE")) { rename { "libvterm-LICENSE" } }
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
		testInstrumentationRunner =
			providers.gradleProperty("dryssh.runner").orElse("androidx.test.runner.AndroidJUnitRunner").get()
	}
	sourceSets["main"].jniLibs.directories.add("../../target/android/jniLibs")
	sourceSets["main"].assets.directories.add(
		layout.buildDirectory.dir("generated/licenseAssets").get().asFile.absolutePath
	)
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	buildFeatures { compose = true }
}
tasks.named("preBuild") { dependsOn(licenseAssets) }
dependencies {
	implementation(project(":terminal"))
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.13.0")
	testImplementation("junit:junit:4.13.2")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	androidTestImplementation("androidx.test:runner:1.7.0")
	androidTestImplementation("androidx.test:core:1.7.0")
	androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
