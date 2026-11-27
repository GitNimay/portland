import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Signing secrets come from local.properties (git-ignored) on a dev machine, or from env vars in CI
// (e.g. release.password <- RELEASE_PASSWORD). No cloud keys are ever built in: each user connects their own
// R2 bucket in Settings, and the keys stay encrypted on their phone.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(key: String): String? =
    System.getenv(key.replace(Regex("([a-z])([A-Z])"), "$1_$2").replace('.', '_').uppercase()) ?: local.getProperty(key)
// CI passes the run number so every published build is newer than the last.
val ciVersion = System.getenv("VERSION_CODE")?.toInt()

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") // licence/debug leftovers of dependencies
    }
    // The app is English-only; dependencies bring dozens of unused translations.
    androidResources.localeFilters += "en"
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
    implementation(libs.core.ktx)
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
    baselineProfile(project(":baselineprofile"))
    implementation(libs.ytdl)
    implementation(libs.ytdl.ffmpeg)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
}

// Baseline profile: the app's own hot code (feed, viewer, glass) compiled ahead of time at install, so the first
// launch and first scroll don't run interpreted. Generated on a device with
//   ./gradlew :app:generateReleaseBaselineProfile -Pabis=x86_64
// and committed under src/release/generated; release builds only merge the committed file.
baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

// The profile is recorded from a separate install (app.portland.bp), so generating one can never replace the real
// app (the test runner uninstalls its target) or touch its library. It never connects a bucket.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.applicationId.set("app.portland.bp")
    }
}
