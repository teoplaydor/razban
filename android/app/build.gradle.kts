plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.razban.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.razban.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 36
        versionName = "0.1.35"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // App-only update channel (secret header gated in Caddy). The key is
        // injected from the UPDATE_KEY CI secret at build time — never in git.
        buildConfigField("String", "UPDATE_KEY", "\"${System.getenv("UPDATE_KEY") ?: ""}\"")
        // PUBLIC update channel (GitHub Pages, no secret) → points at the
        // secret-free android-public APK. The private Beget channel handed
        // secret-embedding APKs to public users, and its manifest had gone stale
        // (0.1.10 < shipped 0.1.13 → every client saw "up to date"). The
        // X-Razban-Update-Key header is still sent but GitHub Pages ignores it.
        buildConfigField("String", "UPDATE_URL", "\"https://teoplaydor.github.io/razban/app-update.json\"")

        // Only ship the ABIs sing-box's libbox + byedpi are built for.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // CI injects a keystore via env; locally an unsigned/debug build is fine.
        create("release") {
            val ksPath = System.getenv("RAZBAN_KEYSTORE")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("RAZBAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAZBAN_KEY_ALIAS")
                keyPassword = System.getenv("RAZBAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release signing config only when a keystore is present,
            // otherwise gradle would fail; CI sets the env vars.
            if (System.getenv("RAZBAN_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // libbox + byedpi .so are already per-ABI; don't compress them twice.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // The sing-box core, built by gomobile into app/libs/libbox.aar.
    // CI builds this from the pinned sing-box tag before assembling the APK.
    // If the file is absent the build fails fast with a clear message (see
    // the check task below).
    implementation(fileTree("libs") { include("*.aar") })

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // WebView host for the React UI (same interface as desktop): asset loader
    // serves the bundled dist over https so ES modules load; document-start
    // script injects the chrome.webview shim before the app boots.
    implementation("androidx.webkit:webkit:1.12.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// Fail early with a human message if libbox.aar wasn't produced.
tasks.register("checkLibbox") {
    doLast {
        val aar = file("libs/libbox.aar")
        if (!aar.exists()) {
            throw GradleException(
                "Missing app/libs/libbox.aar. Build it first:\n" +
                "  bash ../build-libbox.sh   (needs Go 1.24 + Android NDK)\n" +
                "or let the GitHub Actions 'android' workflow build it on CI."
            )
        }
    }
}
tasks.named("preBuild") { dependsOn("checkLibbox") }
