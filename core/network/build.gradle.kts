plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.littlekitty.xiaokele.core.network"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        // Provisional compatibility baseline because target devices are not yet
        // provided: MIUI 14 may ship on Android 12 or 13 depending on device.
        // Final value is locked after real device model info is available.
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)

    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
