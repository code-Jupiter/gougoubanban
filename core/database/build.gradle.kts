plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.littlekitty.xiaokele.core.database"
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

    val robolectricTestUserHome = File(
        System.getProperty("java.io.tmpdir"),
        "littlekitty-core-database-robolectric-home",
    )

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("user.home", robolectricTestUserHome.absolutePath)
                test.doFirst { robolectricTestUserHome.mkdirs() }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
