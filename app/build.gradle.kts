plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

fun stringBuildConfig(name: String, defaultValue: String): String {
    val value = (findProperty(name) as String?)?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultValue
    return "\"$value\""
}

android {
    namespace = "com.klic.mobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.klic.mobile.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.4.2"
        buildConfigField("String", "KLIC_API_ORIGIN", stringBuildConfig("KLIC_API_ORIGIN", "https://api.89.34.230.2.sslip.io"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)

    // Realtime + media
    implementation(libs.socketio.client)
    implementation(libs.livekit.android)

    // Push (FCM) — wakes the app to ring incoming calls when backgrounded/killed
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Animations
    implementation(libs.lottie.compose)

    // Image loading (avatars, SVG stickers)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
}
