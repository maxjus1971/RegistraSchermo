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
        versionCode = 3
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}