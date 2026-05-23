plugins {
    id("com.android.library")
    id("com.chaquo.python")
}

android {
    namespace = "tech.torlando.eridanus.rns.py"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // event_bridge.py reflectively calls into this module's Kotlin
        // classes (chaquopy PyObject.call). These keep rules must reach
        // the consuming :app python flavor's R8 run — see consumer-rules.pro.
        consumerProguardFiles("consumer-rules.pro")

        // Chaquopy ships a Python interpreter as a native library per ABI.
        // Python 3.12+ is 64-bit only — restrict to arm64-v8a (real devices)
        // + x86_64 (emulators). The :app python flavor inherits this via
        // dependency resolution.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

chaquopy {
    defaultConfig {
        // 3.12 is the highest python with broad pip wheel availability under
        // chaquopy 17.
        version = "3.12"

        // pip-install upstream Reticulum from PyPI (pinned to 1.2.5). We
        // also install PyCA `cryptography` — a native wheel from Chaquopy's
        // prebuilt package repo — so RNS uses fast native crypto via
        // RNS/Cryptography/Provider.py instead of its slow pure-python
        // pure25519/aes fallback. (`rns` declares cryptography itself; the
        // explicit floor just matches the wheel Chaquopy ships.)
        //
        // This replaced an earlier setup that vendored the whole Reticulum
        // repo as a git submodule on the chaquopy sourceSet path. pip pulls
        // only the RNS package (not the repo's docs/Examples/tests), so the
        // python APK is actually smaller despite adding native crypto, and
        // there's no submodule for CI/contributors to init.
        pip {
            install("rns==1.2.5")
            install("cryptography>=42.0.0")
        }
    }
}

val coroutinesVersion: String by project

dependencies {
    implementation(project(":eridanus-rns-api"))
    // delay() in PyRnsBackend.restart() and the boot-worker executor in
    // PyReticulumService both need kotlinx-coroutines; backend-kt picks this
    // up transitively from rns-android but backend-py has no such carrier.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
}
