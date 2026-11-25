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
        versionCode = 5
        versionName = "5.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(project(":holos-onnx"))
}
