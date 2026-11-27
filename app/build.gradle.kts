import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.holos.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.holos.reader"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "5.2.0"
    }

    val keystorePath = System.getenv("KEYSTORE_FILE")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank() && File(keystorePath).exists()
    if (project.hasProperty("requireReleaseSigning") && !hasReleaseKeystore) {
        throw GradleException("requireReleaseSigning is set but KEYSTORE_FILE is missing or unreadable")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("enableAbiSplits")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("gz", "onnx")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":holos-onnx"))
    testImplementation("junit:junit:4.13.2")
}
