plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.davexh.shrinky"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.davexh.shrinky"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        resourceConfigurations += listOf("en")
    }
    // Stable signing key for CI releases (set from repo secrets by the workflow). Without it, the debug key is used.
    val ciKeystore: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // CI key when provided, otherwise the debug key so the APK still installs directly.
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version",
                "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "kotlin/**",
            )
        }
    }
}


// No Material library on purpose: the UI is built on foundation only to keep the APK small.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
}
