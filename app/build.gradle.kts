plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.littlekitty.xiaokele"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.dogcompanion.app"
        // Provisional compatibility baseline because target devices are not yet
        // provided: MIUI 14 may ship on Android 12 or 13 depending on device.
        // Final value is locked after real device model info is available.
        minSdk = 31
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"

        // Acceptance provenance: which commit produced this APK.
        val gitCommit: String = runCatching {
            providers.exec {
                workingDir(rootDir)
                commandLine("git", "rev-parse", "--short=8", "HEAD")
            }.standardOutput.asText.get().trim()
        }.getOrDefault("unknown")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("$buildDir/signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    val prepareDebugKeystore by tasks.registering(Copy::class) {
        from(File(System.getProperty("user.home"), ".android/debug.keystore"))
        into(layout.buildDirectory.dir("signing"))
        onlyIf { layout.buildDirectory.file("signing/debug.keystore").get().asFile.exists().not() }
    }

    tasks.matching { it.name == "validateSigningDebug" }.configureEach {
        dependsOn(prepareDebugKeystore)
    }

    val robolectricTestUserHome = File(
        System.getProperty("java.io.tmpdir"),
        "littlekitty-app-robolectric-home",
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
