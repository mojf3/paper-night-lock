plugins { id("com.android.application") }

android {
    namespace = "com.paper.nightlock"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.paper.nightlock"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.paper.nightlock.SmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
