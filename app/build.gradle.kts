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

    // ABI splits for GeckoView flavor to keep APK size reasonable
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

    kotlinOptions {
        jvmTarget = "17"
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
    // Using ABI-specific AARs (~85MB each) instead of fat AAR (226MB)
    // to reduce D8/R8 memory pressure during build
    "geckoImplementation"("org.mozilla.geckoview:geckoview-arm64-v8a:150.0.20260511200624")
    "geckoImplementation"("org.mozilla.geckoview:geckoview-x86_64:150.0.20260511200624")

    debugImplementation("androidx.compose.ui:ui-tooling")
}