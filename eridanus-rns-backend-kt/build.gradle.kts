plugins {
    id("com.android.library")
}

android {
    namespace = "tech.torlando.eridanus.rns.kt"
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
    implementation(project(":eridanus-rns-api"))
    // `implementation`, not `api` — eridanus app code MUST go through the
    // seam, not transitively pull in reticulum-kt symbols. This is the
    // architectural enforcer until the Detekt rule lands.
    implementation("com.github.torlando-tech.reticulum-kt:rns-android:v0.0.19")
}
