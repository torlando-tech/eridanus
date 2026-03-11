# Add project specific ProGuard rules here.

# ===== Reticulum =====
# Reticulum uses reflection for interface adapters
-keep class network.reticulum.** { *; }
-keepclassmembers class network.reticulum.** { *; }
-dontwarn network.reticulum.**

# ===== CBOR =====
# RRC protocol serialization
-keep class co.nstant.in.cbor.** { *; }
-keepclassmembers class co.nstant.in.cbor.** { *; }

# ===== Room =====
# Database entities and DAOs
-keep class tech.torlando.eridanus.data.local.entities.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ===== kotlinx-serialization =====
# Uses reflection for serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== Kotlin Coroutines =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===== Google Tink / security-crypto =====
# Missing annotations from optional dependencies
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# ===== General =====
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
