import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- release signing -------------------------------------------------------------------------
// Copy keystore.properties.example -> keystore.properties (git-ignored) and fill it in.
// Without it, `bundleRelease`/`assembleRelease` fall back to the debug key so the build still
// completes for local testing; such artefacts are NOT uploadable to Play.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) keystoreProperties.load(FileInputStream(keystorePropertiesFile))

val appId = (project.findProperty("janggi.applicationId") as String?) ?: "com.example.janggiai"
val appVersionCode = ((project.findProperty("janggi.versionCode") as String?) ?: "1").toInt()
val appVersionName = (project.findProperty("janggi.versionName") as String?) ?: "1.0.0"
val abis = (project.findProperty("janggi.abis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    // Kotlin package / R / BuildConfig namespace. Keep as is; only applicationId is the store identity.
    namespace = "com.example.janggiai"
    // Google Play: new apps and updates must target API 36 since 2026-08-31 (extension to 2026-11-01 possible).
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += abis }
    }

    // Same NDK pin as :engine. The app module strips the packaged .so with its own NDK; without this it falls back to
    // AGP's default NDK (27.0.x), which is usually not installed, and logs "Unable to strip the following libraries".
    ndkVersion = (project.findProperty("janggi.ndkVersion") as String?) ?: "27.1.12297006"

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ship native symbol tables inside the AAB so Play Console can symbolicate libjanggi_engine.so crashes.
            // Does not affect APK size (symbols are stripped from the packaged .so).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else {
                logger.warn("WARNING: keystore.properties not found - release build signed with the DEBUG key (not uploadable to Play).")
                signingConfigs.getByName("debug")
            }
        }
    }

    bundle {
        // One AAB, Play serves the right ABI; keep language/density splitting on (default).
        abi { enableSplit = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
        jniLibs { useLegacyPackaging = false }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":game"))
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<Test> { testLogging { events("passed", "failed", "skipped") } }
