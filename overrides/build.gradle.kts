plugins {
    id("com.android.application")
}

android {
    namespace = "it.example.registraschermo"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.example.registraschermo"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}