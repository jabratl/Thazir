plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version: change ONLY this line to bump the version (the workflow reads it).
val appVersionName = "0.10.7"
// versionCode = GitHub run number (always goes up, so Android accepts the update).
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.mgb.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mgb.keyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = appVersionName
        // owner/repo is filled automatically by GitHub Actions (used by the in-app updater)
        buildConfigField("String", "GITHUB_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        val ks = System.getenv("KEYSTORE_PATH")
        if (!ks.isNullOrEmpty()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

// Java sources (the Avro engine) contain Bangla letters: always read them as UTF-8
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
