// Android library: Fairy-Stockfish (pinned, see fsf.properties) compiled with the NDK into
// libjanggi_engine.so, plus the Kotlin JanggiEngine API over UCI.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The pin file is the single source of truth for the vendored engine revision; fail loudly if it is missing.
val fsfProps = Properties().apply { file("fsf.properties").inputStream().use { load(it) } }
require(fsfProps.getProperty("FSF_COMMIT")?.length == 40) { "engine/fsf.properties must pin FSF_COMMIT" }
val abis = (project.findProperty("janggi.abis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.example.janggiai.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                // Release-grade optimisation for both build types: the engine must be fast even
                // in debug builds or analysis depth on the emulator is meaningless.
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Pin the NDK for reproducible native builds.  Android Studio installs it on demand; change
    // only together with a re-verification of tools/desktop-jni-test.  Override with
    // -Pjanggi.ndkVersion=... if your SDK has a different side-by-side NDK installed.
    ndkVersion = (project.findProperty("janggi.ndkVersion") as String?) ?: "27.1.12297006"

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = false }   // uncompressed, page-aligned .so (16 KB page size ready)
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":game"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}

tasks.withType<Test> { testLogging { events("passed", "failed", "skipped") } }
