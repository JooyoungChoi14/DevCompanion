plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.devcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devcompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // ── Product Flavors ──────────────────────────────────────────
    // `free`: system WebView (local build friendly, small APK)
    // `gecko`: GeckoView engine (CI-only build, larger APK)
    flavorDimensions += "engine"
    productFlavors {
        create("free") {
            dimension = "engine"
            // No GeckoView dependency — uses system WebView
        }
        create("gecko") {
            dimension = "engine"
            // GeckoView engine — ABI-specific AARs to reduce build memory
            // Fat AAR (geckoview) is 226MB, ABI-specific is ~85MB
        }
    }

    // ── ABI configuration ────────────────────────────────────────
    // free flavor: no native libs, no ABI split needed.
    // gecko flavor: GeckoView ships native libs for arm64-v8a and x86_64;
    // split into per-ABI APKs to keep size reasonable (~100MB vs ~230MB universal).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // GeckoView native libs need to be packaged without compression
    packaging {
        jniLibs {
            // GeckoView .so files should not be compressed
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Compose BOM — compatible with Kotlin 2.1
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // OkHttp for CDP WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // Vico for charts
    implementation("com.patrykandpatrick.vico:compose-m3:1.13.1")
    implementation("com.patrykandpatrick.vico:core:1.13.1")

    // NanoHTTPD for Bridge API server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // CommonMark — spec-compliant markdown parsing
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    // Encrypted SharedPreferences for secure API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── GeckoView (gecko flavor only) ────────────────────────────
    // Fat AAR (226MB) — CI-only build. Per-ABI AARs conflict on
    // the `geckoview` capability, so we use the fat AAR and split by ABI.
    // ABI-specific AARs: arm64-v8a (~85MB), x86_64 (~89MB)
    "geckoImplementation"("org.mozilla.geckoview:geckoview:150.0.20260511200624")

    debugImplementation("androidx.compose.ui:ui-tooling")
}