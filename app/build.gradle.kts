import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets come from local.properties (git-ignored) on a dev machine, or from env vars in CI
// (e.g. r2.accessKey <- R2_ACCESS_KEY). Missing R2/AI values = local-only mode.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(key: String): String? =
    System.getenv(key.replace(Regex("([a-z])([A-Z])"), "$1_$2").replace('.', '_').uppercase()) ?: local.getProperty(key)
fun field(key: String) = "\"${secret(key).orEmpty()}\""
// CI passes the run number so every published build is newer than the last.
val ciVersion = System.getenv("VERSION_CODE")?.toInt()

android {
    namespace = "app.portland"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.portland"
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersion ?: 1
        versionName = "1.0.${ciVersion ?: 0}"
        buildConfigField("String", "R2_ACCOUNT_ID", field("r2.accountId"))
        buildConfigField("String", "R2_ACCESS_KEY", field("r2.accessKey"))
        buildConfigField("String", "R2_SECRET_KEY", field("r2.secretKey"))
        buildConfigField("String", "R2_BUCKET", field("r2.bucket"))
        buildConfigField("String", "AI_TOKEN", field("ai.token"))
        // yt-dlp ships native Python and ffmpeg per ABI (~25 MB each), so release builds carry phones' ABI only.
        // Emulator testing: ./gradlew assembleRelease -Pabis=x86_64
        ndk { abiFilters += (findProperty("abis") as String? ?: "arm64-v8a").split(",") }
    }

    signingConfigs {
        // One permanent key: every self-update must be signed with it, or Android refuses to install it.
        create("release") {
            secret("release.storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = secret("release.password")
            keyAlias = secret("release.alias")
            keyPassword = secret("release.password")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (secret("release.storeFile") != null) "release" else "debug")
        }
    }
    packaging {
        // yt-dlp's Python runtime is loaded from extracted files, not mapped from the APK.
        jniLibs.useLegacyPackaging = true
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
    implementation(libs.backdrop)
    implementation(libs.shapes)
    implementation(libs.work.runtime)
    implementation(libs.profileinstaller)
    implementation(libs.ytdl)
    implementation(libs.ytdl.ffmpeg)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
