plugins {
    id("com.android.application")
}

android {
    namespace = "com.lg.testnotif"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lg.testnotif"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
