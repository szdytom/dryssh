plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    ndkPath = providers.environmentVariable("ANDROID_NDK_HOME").orElse(rootProject.file("../.tools/android-ndk").absolutePath).get()
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += providers.gradleProperty("dryssh.abis").orElse("arm64-v8a,x86_64").get().split(",") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.slf4j:slf4j-api:2.0.20")
}
