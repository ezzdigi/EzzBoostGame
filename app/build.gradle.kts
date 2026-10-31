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

// amneziawg-android pulls androidx.core 1.17.0, which needs compileSdk 36 and AGP 8.9.1+.
// Pin it to 1.15.0 so the project keeps building with compileSdk 35 / AGP 8.7.3.
configurations.configureEach {
    resolutionStrategy {
        force("androidx.core:core:1.15.0", "androidx.core:core-ktx:1.15.0")
    }
}

// The AmneziaWG library also pulls kotlin-stdlib 2.2.x, which the Kotlin 2.0.21 compiler cannot read.
// Compile against stdlib 2.0.21 only; the APK itself still packs the newer runtime stdlib.
configurations.configureEach {
    if (name.endsWith("CompileClasspath")) {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21"
            )
        }
    }
}

// Xray core (libv2ray.aar) is downloaded by the GitHub workflow into app/libs.
// Without it the app still builds, and V2Ray direct connect is disabled.
val xrayAarFile = file("libs/libv2ray.aar")

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    // AmneziaWG backend (obfuscated WireGuard) for Pro Gaming configs that carry Jc/S1/H1... keys.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    if (xrayAarFile.exists()) {
        implementation(files(xrayAarFile))
    }
}
