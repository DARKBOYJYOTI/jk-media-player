import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material never lives in this repository. CI supplies it through GitHub Actions
// secrets, and local builds fall back to the debug key so assembleRelease still works for testing.
val releaseStoreFile: String? = System.getenv("JK_KEYSTORE_FILE")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        File(releaseStoreFile).exists() &&
        !System.getenv("JK_KEYSTORE_PASSWORD").isNullOrBlank() &&
        !System.getenv("JK_KEY_ALIAS").isNullOrBlank() &&
        !System.getenv("JK_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "com.jk.mediaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jk.mediaplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile?.let { File(it) }
                storePassword = System.getenv("JK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JK_KEY_ALIAS")
                keyPassword = System.getenv("JK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // CI hands debug APKs to real devices, so they must carry the same signature as the
            // release builds. With the runner's throwaway debug key every build was a different
            // signature, which made every update install fail.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // R8 + resource shrinking: debug builds run the app in JIT/interpreter mode which is
            // the main reason playback UI felt janky. Release is AOT compiled and much smaller.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Android framework stubs return default values in plain JVM tests, so pure mapping
        // helpers can be tested without an emulator.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Jetpack Compose & Material 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Image & Video Thumbnail Loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Dynamic Theming with Album Art
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media & System MediaSession
    implementation("androidx.media:media:1.7.0")

    // Universal Video & Audio Core: LibVLC
    implementation("org.videolan.android:libvlc-all:3.7.8")


    // Audio ID3 Tag Engine (MP3, FLAC, M4A, OGG)
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")

    // Debugging
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
