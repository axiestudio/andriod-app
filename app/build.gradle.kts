import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials live in mobile/keystore.properties (git-ignored,
// never committed). See SKILL.md §3. Without it, assembleRelease stays unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.axie.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.axie.remote"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Networking for Phase 1+ (WebSocket signaling + MJPEG frames, see SPEC.md §5).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Phase 3 (SPEC.md §2/§3): WebRTC screen streaming — prebuilt libwebrtc
    // with ScreenCapturerAndroid; media is peer-to-peer, signaling rides the
    // CRM API's DB mailbox (MobileSignal rows), no relay server.
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    // QR pairing scanner (offline, no Play Services): fills relay URL + token
    // from the web viewer's QR code (see SPEC.md §5.3).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
