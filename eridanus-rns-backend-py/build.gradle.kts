plugins {
    id("com.android.library")
}

// chaquopy plugin is intentionally NOT applied yet. The python backend is
// scaffolded so the :app module's `python` flavor has something to depend
// on, but the actual python interop (chaquopy + upstream RNS bundle +
// event_bridge.py + PyObject-backed implementations) lives in a follow-up
// change. Until that lands, this module compiles but its PyRnsBackend
// throws on every call; selecting the `python` flavor will fail at runtime
// the moment app code touches RNS. The seam is the deliverable here, not
// the python integration itself.

android {
    namespace = "tech.torlando.eridanus.rns.py"
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
}
