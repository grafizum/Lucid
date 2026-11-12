plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.grafizum.lucid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grafizum.lucid"
        minSdk = 26
        // Play requires API 36 (Android 16) for new apps and updates.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")

        // Every modern phone is arm64; x86_64 keeps emulators working. Dropping
        // the 32-bit ML Kit binaries halves the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The start screen prints which build it is, bottom left, so the version
    // numbers have to be readable from code.
    buildFeatures {
        buildConfig = true
    }
}

// The bundle carries its version in its own file name, the way Repio's builds
// do: Android Studio's "Generate Signed App Bundle" then writes
// Lucid-v1.0-v1-release.aab instead of an anonymous app-release.aab.
base {
    val name = android.defaultConfig.versionName
    val code = android.defaultConfig.versionCode
    archivesName.set("Lucid-v$name-v$code")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device ML: OCR, language identification, translation. No API key.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // The splitting and alignment rules that make the word-by-word card are
    // pure logic, so they are checked on the JVM rather than on a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
