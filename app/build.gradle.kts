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
        versionCode = 3
        versionName = "2.1.0"
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

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20250531")
}
