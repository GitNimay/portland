plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.portland.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Runs on whatever device is connected (the local emulator); no managed devices in CI.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.benchmark.macro)
}
