plugins {
    id("com.android.library")
}

android {
    namespace = "tech.torlando.eridanus.rns"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // No RNS deps. This module defines the contract that backends implement
    // and the app consumes. Adding reticulum-kt or chaquopy here would defeat
    // the purpose of the seam.
}
