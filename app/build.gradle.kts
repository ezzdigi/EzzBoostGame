plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ezzdigi.ezzgameboost"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ezzdigi.ezzgameboost"
        minSdk = 26
        targetSdk = 35
        // CI passes -PappVersion / -PappVersionCode when a GitHub Release is published,
        // so the app version always matches the release tag.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 4
        versionName = (project.findProperty("appVersion") as String?) ?: "2.2.0"
        // Keep the APK small: modern phones only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Optional release signing: set these environment variables (GitHub secrets)
    // to sign with your own key. Without them the release APK is signed with the
    // debug key, which is still installable.
    val ksPath: String? = System.getenv("KEYSTORE_PATH")
    val useOwnKey = !ksPath.isNullOrEmpty()

    signingConfigs {
        if (useOwnKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (useOwnKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Xray core (libv2ray.aar) is downloaded by the GitHub workflow into app/libs.
// Without it the app still builds, and V2Ray direct connect is disabled.
val xrayAarFile = file("libs/libv2ray.aar")

// The AmneziaWG library pulls in androidx.core 1.17.0, which needs compileSdk 36 and AGP 8.9.1.
// Keep the version this project already builds with (compileSdk 35, AGP 8.7.3).
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.15.0")
        force("androidx.core:core-ktx:1.15.0")
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    if (xrayAarFile.exists()) {
        implementation(files(xrayAarFile))
    }
}
