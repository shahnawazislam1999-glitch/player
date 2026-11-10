plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raj.shadowplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raj.app.shadowplayer"
        minSdk = 29
        targetSdk = 34
        // Every GitHub build gets a higher number, so a new APK always installs over the old one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.3.0"
    }

    // Fixed personal signing key (kept in the repo on purpose) so updates install over older builds.
    signingConfigs {
        create("release") {
            storeFile = file("raj-hub.jks")
            storePassword = "rajhub123"
            keyAlias = "rajhub"
            keyPassword = "rajhub123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses newer Java APIs than minSdk 29 has
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.version")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Native video player. 1.4.1 is the newest Media3 that still builds with compileSdk 34 / AGP 8.5.2.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Media session: lets the Bluetooth smart pen (media next / previous buttons) reach the player, also when the app is minimized.
    implementation("androidx.media3:media3-session:1.4.1")

    // YouTube links: stream URLs + captions come from NewPipe Extractor, played by the same ExoPlayer.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // fallback formats for YouTube (HLS / DASH manifests)
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
