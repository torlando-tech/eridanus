import java.util.Base64

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val coroutinesVersion: String by project

// Parse version from git tag (e.g., v1.2.3 -> versionName "1.2.3", versionCode calculated)
// Scheme: major * 10M + minor * 100K + patch * 1K (+ commits for dev builds)
fun getVersionFromTag(): Pair<Int, String> {
    return try {
        val tagName =
            providers.exec {
                commandLine("git", "describe", "--tags", "--exact-match")
            }.standardOutput.asText.get().trim()

        val versionString = tagName.removePrefix("v")
        val parts = versionString.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.split("-")?.get(0)?.toIntOrNull() ?: 0

        val versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000
        Pair(versionCode, versionString)
    } catch (e: Exception) {
        try {
            val describe =
                providers.exec {
                    commandLine("git", "describe", "--tags", "--long")
                }.standardOutput.asText.get().trim()

            val parts = describe.removePrefix("v").split("-")
            val versionPart = parts[0]
            val commitCount = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0

            val versionParts = versionPart.split(".")
            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0

            val versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000 + commitCount
            val versionName = "$major.$minor.$patch.${commitCount.toString().padStart(4, '0')}-dev"

            println("Dev build: $versionName (versionCode=$versionCode)")
            Pair(versionCode, versionName)
        } catch (e2: Exception) {
            println("Warning: No git tags found, using fallback version")
            Pair(1_000_000, "0.0.0-dev")
        }
    }
}

val (versionCodeValue, versionNameValue) = getVersionFromTag()

android {
    namespace = "tech.torlando.eridanus"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.torlando.eridanus"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Track whether release signing is configured
    val releaseSigningConfigured =
        run {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            !keystoreFile.isNullOrEmpty() && !keystorePassword.isNullOrEmpty() &&
                !keyAlias.isNullOrEmpty() && !keyPassword.isNullOrEmpty()
        }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                val keystoreFile = System.getenv("KEYSTORE_FILE")!!
                val keystorePassword = System.getenv("KEYSTORE_PASSWORD")!!
                val keyAlias = System.getenv("KEY_ALIAS")!!
                val keyPassword = System.getenv("KEY_PASSWORD")!!

                try {
                    val keystoreDir = file("${layout.buildDirectory.get().asFile}/keystore")
                    keystoreDir.mkdirs()
                    val decodedKeystore = file("$keystoreDir/release.keystore")

                    val cleanedKeystoreFile = keystoreFile.replace("\\s".toRegex(), "")
                    decodedKeystore.writeBytes(Base64.getDecoder().decode(cleanedKeystoreFile))

                    storeFile = decodedKeystore
                    storePassword = keystorePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword

                    println("Release signing configured from environment variables")
                } catch (e: IllegalArgumentException) {
                    throw GradleException(
                        "Failed to decode KEYSTORE_FILE: ${e.message}\n" +
                            "To encode: base64 -w 0 your-keystore.jks",
                    )
                }
            }
        } else {
            println("Release keystore env vars not set — release builds will " +
                "fall back to debug signing (installable, but NOT a real release)")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // When the real keystore is configured (tagged releases via
            // release.yml), sign with it. Otherwise fall back to the debug
            // keystore rather than producing an *unsigned* APK — an unsigned
            // release APK can't be installed on any device, which makes the
            // PR-CI `release-apks` artifact useless for testing. Debug-signed
            // release APKs are still R8-minified, just not distributable.
            // release.yml's apksigner gate rejects debug-signed APKs, so a
            // missing-secret situation can never ship a real release.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // Distinct applicationId so a debug build installs *alongside*
            // the release-signed app instead of being blocked by the
            // signature mismatch (debug keystore vs release keystore can
            // never replace each other without an uninstall that wipes the
            // identity/rooms). Stacks on the per-flavor suffix, e.g.
            // tech.torlando.eridanus.python.debug. Per-variant launcher
            // labels live in app/src/<flavor>Debug/res/values/strings.xml
            // ("Eridanus Test") so the two are distinguishable.
            applicationIdSuffix = ".debug"
        }
    }

    // rnsImpl flavor dimension — see Memory/eridanus/rns-backend-dual-build.md.
    // `kotlin` ships reticulum-kt embedded; `python` embeds chaquopy +
    // upstream RNS. Both attach to a shared instance the same way at
    // runtime; the difference is which code runs the crypto / link state
    // machine / packet framing in-process.
    //
    // Each flavor gets a distinct applicationId (via applicationIdSuffix)
    // so the two can be installed side by side — useful for direct
    // comparison and for two-device parity testing on a single phone.
    // `namespace` stays "tech.torlando.eridanus" (the code package is
    // shared); only the installed app id diverges. Per-flavor app_name
    // strings (app/src/<flavor>/res/values/strings.xml) keep them
    // distinguishable in the launcher.
    flavorDimensions += "rnsImpl"
    productFlavors {
        create("kotlin") {
            dimension = "rnsImpl"
            applicationIdSuffix = ".kotlin"
        }
        create("python") {
            dimension = "rnsImpl"
            applicationIdSuffix = ".python"
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // RrcHub/RrcClient call android.util.Log; let the stubbed
            // android.jar return defaults instead of throwing so the RRC
            // protocol classes can be exercised in plain JVM unit tests.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Backend-neutral RNS contract — app code consumes this; the chosen
    // flavor provides the backend that implements it.
    implementation(project(":eridanus-rns-api"))

    // Flavor-bound backends. The Detekt rule NoDirectReticulumKtImportFromApp
    // (to be added in a follow-up) keeps app code from depending on either
    // backend's internals — only the API.
    "kotlinImplementation"(project(":eridanus-rns-backend-kt"))
    "pythonImplementation"(project(":eridanus-rns-backend-py"))

    // CBOR encoding (RRC protocol uses CBOR)
    implementation("co.nstant.in:cbor:0.9")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Lucide icons
    implementation("com.composables:icons-lucide-android:1.1.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("printVersion") {
    doLast {
        println("versionName: ${android.defaultConfig.versionName}")
        println("versionCode: ${android.defaultConfig.versionCode}")
    }
}
