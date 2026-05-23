plugins {
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.android.application") version "9.1.0" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    // Chaquopy bundles a Python interpreter into the python flavor APK and
    // makes upstream Reticulum (pip-installed by :eridanus-rns-backend-py)
    // callable from Kotlin via PyObject. Only :eridanus-rns-backend-py applies
    // it — the kotlin flavor APK never pulls it in.
    id("com.chaquo.python") version "17.0.0" apply false
}
