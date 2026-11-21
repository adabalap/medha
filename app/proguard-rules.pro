# R8 / ProGuard rules for Medha.
#
# Read this before changing anything here. The failure mode these rules
# prevent is not a crash — it is silent, plausible-looking wrong behaviour in
# release builds only, which is the most expensive kind of bug this project
# can ship.

# ---------------------------------------------------------------------------
# LiteRT-LM, reached by reflection
# ---------------------------------------------------------------------------
#
# LlmEngine does not link against the LiteRT-LM API directly. It probes for
# methods by name at runtime (`getMethod("sendMessageAsync")`,
# `getMethod("getText")`, and a fallback chain through getContent/getContents)
# because that surface has changed between AAR releases, and a reflective
# probe degrades gracefully where a hard link would refuse to load at all.
#
# R8 cannot see reflective lookups. Left to itself it renames
# `sendMessageAsync` to something like `a`, the probe finds nothing, and
# extractText() falls through to `toString()` — which returns a Kotlin data
# class dump like `Message(role=ASSISTANT, content=[Text(text=Hello)])`
# instead of the reply. That never throws. It just makes every response
# garbled in release builds while debug builds stay perfect. This project has
# already shipped that exact bug once, from a different cause; a build flag
# must not be allowed to resurrect it.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** {
    public *;
}

# ---------------------------------------------------------------------------
# AI Edge RAG SDK, also reached by reflection
# ---------------------------------------------------------------------------
#
# AiEdgeEmbedder resolves GeckoEmbeddingModel via Class.forName and then
# probes several constructor shapes and several embedding method names. Same
# reasoning as above, with the same silent failure: a renamed class means the
# embedder reports "SDK not on the classpath" and RAG quietly drops to lexical
# search, which looks like a configuration choice rather than a bug.
#
# -dontwarn because the dependency is optional (commented out in
# build.gradle.kts by default); without it R8 fails the build on missing
# references when the SDK is absent.
-keep class com.google.ai.edge.localagents.** { *; }
-dontwarn com.google.ai.edge.localagents.**
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
#
# Generated serializers are referenced reflectively via the companion. Losing
# them turns every request body into a runtime SerializationException, which
# at least fails loudly — but the API would be entirely dead.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static <1>$Companion Companion;
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Every @Serializable request/response class in the HTTP surface.
-keep @kotlinx.serialization.Serializable class com.adabala.medha.** { *; }

# ---------------------------------------------------------------------------
# Ktor + its engine
# ---------------------------------------------------------------------------
#
# Ktor resolves engines and plugins through ServiceLoader and reflection.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
# Ktor pulls in an SLF4J binding lookup; absent one, it logs a warning rather
# than failing, but R8 still needs to be told the references are expected.
-keep class org.slf4j.** { *; }

# ---------------------------------------------------------------------------
# Medha's own entry points
# ---------------------------------------------------------------------------
#
# Referenced from the manifest by name, so R8 keeps them anyway — listed
# explicitly so an accidental rename shows up here rather than as a
# ClassNotFoundException at launch.
-keep class com.adabala.medha.MedhaApplication { *; }
-keep class com.adabala.medha.MainActivity { *; }
-keep class com.adabala.medha.InferenceService { *; }
-keep class com.adabala.medha.auth.AccessRequestActivity { *; }
-keep class com.adabala.medha.notify.MedhaWidgetProvider { *; }

# MedhaAccessContract is the published integration surface. Third-party apps
# match on its string values, which are inlined, but keeping the class means
# stack traces from the consent flow stay readable.
-keep class com.adabala.medha.auth.MedhaAccessContract { *; }

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
#
# Crash dumps are the main tool for debugging a release build on someone
# else's phone. Obfuscated frames make them nearly useless, so keep line
# numbers and map the source file attribute.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
