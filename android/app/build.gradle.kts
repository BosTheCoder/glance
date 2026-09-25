import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key lives outside the repo; without it the release APK is debug-signed.
val keystoreFile = file(System.getProperty("user.home") + "/.config/glance/keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.bosthecoder.glance"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.bosthecoder.glance"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.10.1"
    }

    signingConfigs {
        if (keystoreFile.exists()) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8: shrinks the AndroidX classes we don't use
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// Newest releases that still build against compileSdk 35 (core 1.17+ and activity 1.11+ need 36).
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
