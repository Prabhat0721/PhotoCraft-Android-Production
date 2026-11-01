import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.prabhat.photocraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prabhat.photocraft"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.0-m5.3"
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

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
