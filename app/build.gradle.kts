plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.alarmclock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.alarmclock"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Постоянный ключ подписи: новые версии APK устанавливаются поверх старых без удаления
    signingConfigs {
        create("fixed") {
            storeFile = file("alarmclock.keystore")
            storePassword = "alarmclock"
            keyAlias = "alarmclock"
            keyPassword = "alarmclock"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
