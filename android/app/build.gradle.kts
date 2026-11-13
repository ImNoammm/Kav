import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The key Kav signs its requests to its own server with. It never goes in the repository: local.properties here,
// a secret for the release build on GitHub.
val liveKey: String = System.getenv("KAV_LIVE_KEY")?.takeIf { it.isNotBlank() }
    ?: Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
        .getProperty("kav.liveKey").orEmpty()

android {
    namespace = "uk.noammm.kav"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.noammm.kav"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MapLibre's renderer is native code. Every phone Kav can reach is arm64;
        // x86_64 stays so the release APK still installs on the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "KAV_LIVE_KEY", "\"$liveKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key: there is no store listing, and an update
            // only installs over the previous one if both carry the same signature.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    androidResources { ignoreAssetsPattern = "*.kav:*.kav.gz" }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1")
    // The same OkHttp MapLibre ships with, for its client (GovMapTiles).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Puts the app's and Compose's baseline profiles in place for Android to compile ahead of time. Compose brings 1.3.1,
    // which gives up on Android 15 and newer ("unsupported ART version"), so those phones never got either profile.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // A script in Google's Street View frame, so one finger turns it (StreetView.kt).
    implementation("androidx.webkit:webkit:1.18.0")
    // Reading a bus QR code with the camera, and drawing a ticket's QR for the inspector.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
