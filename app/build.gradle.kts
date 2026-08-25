import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives in keystore.properties (untracked, next to the
// gitignored release.keystore). Builds without it still work — the release
// build type just stays unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fancyshark.wpdialer"
    // 36: Play requires new apps to target the latest API (deadline Aug 2026).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fancyshark.wpdialer"
        // 33 floor: Android 13 keeps per-app languages, notification
        // permission, and themed icons. Audio routing has a CallAudioState
        // compat path for 33 (CallEndpoint is 34+).
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Embed native symbol tables in the AAB so Play can symbolicate
            // crashes in the androidx native libs (removes a Console warning).
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // R8 smoke-test build: identical to release but debug-signed and
        // side-by-side installable, so testing minification never requires
        // uninstalling the real (data-carrying) app.
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
    bundle {
        // The in-app language picker needs all locales installed — Play's
        // per-language splits would strip fr/ar from most installs. The
        // whole app is ~3 MB; splitting saves nothing worth the breakage.
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    // Installs the baseline profiles bundled with the Compose libraries so
    // ART pre-compiles the hot paths — faster cold start and first scroll.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.55")
    // Tip jar. Products must exist in Play Console; until then (or on
    // devices without Play) the query returns nothing and the UI hides.
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    // Billing pulls in a pre-1.3 androidx.fragment transitively, which
    // trips the ActivityResult lint check on release builds — pin current.
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
