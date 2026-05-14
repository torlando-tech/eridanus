plugins {
    id("com.android.library")
    id("com.chaquo.python")
}

android {
    namespace = "tech.torlando.eridanus.rns.py"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

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
        // chaquopy 17. Reticulum 1.2.5 runs cleanly on it.
        version = "3.12"

        // No pip installs. Reticulum is pure-python end to end — its
        // `cryptography` and `pyserial` dependencies are optional. Without
        // PyCA cryptography on the python path, RNS/Cryptography/Provider.py
        // falls back to the bundled pure-python crypto under
        // RNS/Cryptography/aes/ and RNS/Cryptography/pure25519/. We don't
        // need pyserial because eridanus only operates as a shared-instance
        // client (LocalClientInterface over loopback TCP — pure stdlib).
        pip {
            // intentionally empty — see comment above.
        }
    }

    // Reticulum source lives at eridanus-rns-backend-py/Reticulum/ (git
    // submodule pinned to 1.2.5). Tell chaquopy to add it to the python
    // import path so `import RNS` works from event_bridge.py and from
    // PyObject.getModule("RNS") in Kotlin. `src/main/python` (where
    // event_bridge.py lives) is on the path by default.
    sourceSets {
        getByName("main") {
            srcDir("Reticulum")
        }
    }
}

dependencies {
    implementation(project(":eridanus-rns-api"))
}
