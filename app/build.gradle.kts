import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvpptx.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvpptx.reader"
        minSdk = 24
        // Targeting 29 keeps legacy storage access, so the app can read .pptx files straight
        // from USB sticks on Android 10-11 projectors. It is sideloaded, never published.
        targetSdk = 29
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
