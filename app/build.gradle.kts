plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = "0.0.331"
// CI sets GITHUB_RUN_NUMBER, so every build has a higher versionCode (needed for updates)
// CI provides "owner/repo"; the in-app updater reads releases from it
val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: ""
val appVersionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// S16: CI fingerprint of all non-ext sources; ext-only hot updates (ExtUpdater) are allowed only when it matches
val nativeHash = System.getenv("MGB_NATIVE_HASH") ?: ""

android {
    namespace = "com.jabra.mgb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jabra.mgb"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
        buildConfigField("String", "NATIVE_HASH", "\"$nativeHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        val ksPath = System.getenv("KEYSTORE_PATH")
        if (!ksPath.isNullOrEmpty()) {
            create("mgb") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("mgb")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("mgb")?.let { signingConfig = it }
        }
    }

    // S7: extra manifest (service, FileProvider, permissions) merged into both build types
    sourceSets {
        getByName("debug") { manifest.srcFile("src/extra/AndroidManifest.xml") }
        getByName("release") { manifest.srcFile("src/extra/AndroidManifest.xml") }
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1") // S36: PlayerActivity
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1") // S39: web streams (.m3u8)
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1") // S39: web streams (.mpd)
    implementation("com.google.android.gms:play-services-auth:21.2.0") // 0.0.311: Google sign-in (Authorization API) for Passport + Drive
    implementation("androidx.biometric:biometric:1.1.0") // S30: unlock for saved passwords
    implementation("org.tensorflow:tensorflow-lite:2.14.0") // 0.0.232 (P8a): on-device picture check (SocialNsfw, needs assets/nsfw/model.tflite)
}
