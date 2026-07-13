plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fusionhealth.diagnostic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fusionhealth.diagnostic"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-wp1-build-b"
    }

    // Release signing is optional at this stage: if ANDROID_KEYSTORE_PATH points to a
    // real file (populated from a GitHub Actions secret), release builds use it. If not,
    // release builds fall back to Gradle's own debug signing so CI still produces an
    // installable APK before the release keystore secret exists. No keystore material is
    // ever read from or written to the repository itself.
    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

    // Logs only which signing config name is selected (never any secret/alias/password
    // value) so CI evidence can confirm release signing was actually used, not just that
    // the keystore secret exists.
    println("Fusion Health: release build will sign with signingConfig = '${if (hasReleaseKeystore) "release" else "debug"}'")

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
