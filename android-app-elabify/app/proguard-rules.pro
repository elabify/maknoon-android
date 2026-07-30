# R8 keep-rules for the Maknoon release build (task #256).
#
# This is a CRYPTO + HARDWARE-WALLET app with a large JNI + reflection +
# serialization surface, so the rules are deliberately CONSERVATIVE: we keep the
# whole native/reflective surface (and all of our own code) and let R8 shrink +
# obfuscate only the safe third-party remainder. That produces a mapping.txt for
# crash deobfuscation and trims dead third-party code without renaming anything
# that is looked up by name from native code, reflection, or JSON.
#
# IMPORTANT: R8 breakage is a RUNTIME failure (a class/method looked up by name
# was renamed or stripped), not a build failure. A green release build does NOT
# prove safety. This config must pass a full on-device regression (all 5
# networks, hardware wallets, passport NFC, mini-apps, backup/restore, YubiKey)
# before it ships.

# Preserve annotations, signatures, and line numbers so stack traces stay useful
# after deobfuscation via the uploaded mapping.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Our own code (app + SDK): keep entirely. -----------------------------
# The SDK + app lean heavily on JSON (backup, credentials, models) and reflection;
# keeping com.elabify.** means our crash frames are already readable and nothing
# we serialize/reflect is renamed. Third-party libs below still get shrunk.
-keep class com.elabify.** { *; }
-keep interface com.elabify.** { *; }

# ---- Native methods (JNI entry points must keep their names). --------------
-keepclasseswithmembernames class * { native <methods>; }

# ---- TrustWalletCore (JNI). -------------------------------------------------
-keep class com.trustwallet.** { *; }
-keep class wallet.core.** { *; }
-dontwarn wallet.core.**
-dontwarn com.trustwallet.**

# ---- JNA + uniffi (the in-house Rust cores bind via JNA callbacks). --------
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ---- BouncyCastle (reflection-heavy provider). ------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---- ICAO 9303 eMRTD passport reading (jmrtd + scuba). ----------------------
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**

# ---- Reown WalletKit (serialization / reflection). --------------------------
-keep class com.reown.** { *; }
-dontwarn com.reown.**

# ---- Yubico yubikit. --------------------------------------------------------
-keep class com.yubico.** { *; }
-dontwarn com.yubico.**

# ---- kotlinx.serialization (belt-and-braces on top of the plugin's rules). --
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    <fields>;
}
-if @kotlinx.serialization.Serializable class *
-keepclassmembers class <1> { static <1>$Companion Companion; }

# ---- Room. ------------------------------------------------------------------
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# ---- Kotlin metadata + coroutines internals. --------------------------------
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ---- Enums accessed by valueOf/name (common in wire/config parsing). --------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Missing transitive classes (R8 missing_rules.txt). ---------------------
# Google Tink references errorprone annotations that aren't on the runtime
# classpath (compile-only). Safe to ignore; they are not used at runtime.
-dontwarn com.google.errorprone.annotations.CheckReturnValue
