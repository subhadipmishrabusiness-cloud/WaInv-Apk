plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.wainv.wrapper"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.wainv.wrapper"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
