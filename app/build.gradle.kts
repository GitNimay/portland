import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// R2 credentials live in local.properties (git-ignored). Empty values = local-only mode.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun r2(key: String) = "\"${local.getProperty(key, "")}\""

android {
    namespace = "app.portland"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.portland"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "R2_ACCOUNT_ID", r2("r2.accountId"))
        buildConfigField("String", "R2_ACCESS_KEY", r2("r2.accessKey"))
        buildConfigField("String", "R2_SECRET_KEY", r2("r2.secretKey"))
        buildConfigField("String", "R2_BUCKET", r2("r2.bucket"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideloaded app: sign release with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.telephoto.coil)
    implementation(libs.work.runtime)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
