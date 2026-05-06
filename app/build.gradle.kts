plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.laundryhub.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laundryhub.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.1"

        // ====================================
        // CONFIGURE SUA URL AQUI
        // ====================================
        buildConfigField("String", "DISPLAY_URL", "\"https://lavala.vercel.app/display/lavala\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = "laundryhub2026"
            keyAlias = "laundryhub"
            keyPassword = "laundryhub2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
