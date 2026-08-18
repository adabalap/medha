plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// NOTE ON ROOM
//
// This module intentionally does NOT use Room. The v0.1.1 build failure
// ("Unresolved reference 'room'/'Dao'/'Query'/'Insert'" in data/Daos.kt) was
// caused by a leftover Room DAO file with no Room dependency and no annotation
// processor on the classpath. The fix is to delete that file, not to add Room:
// the rest of the data layer already talks to MedhaDatabase's hand-written SQL.
//
// If you ever do want Room back, it needs ALL of:
//   1. id("com.google.devtools.ksp") version "<matching your Kotlin version>"
//      in the root build.gradle.kts (apply false) and applied here.
//   2. implementation("androidx.room:room-runtime:2.6.1")
//      implementation("androidx.room:room-ktx:2.6.1")
//      ksp("androidx.room:room-compiler:2.6.1")
//   3. @Entity/@Dao/@Database annotations restored on the model classes.
// KSP's version is pinned to the Kotlin compiler version, which is the coupling
// this project chose to avoid. Do not add Room piecemeal.
// ---------------------------------------------------------------------------

// Release signing is driven by environment variables so no keystore or password
// ever lands in the repo. Set these as GitHub Actions secrets; when they are
// absent (any local checkout) the release build simply stays unsigned and the
// debug build is unaffected.
val keystorePath: String? = System.getenv("MEDHA_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("MEDHA_KEYSTORE_PASSWORD")
val keyAliasName: String? = System.getenv("MEDHA_KEY_ALIAS")
val keyPassword: String? = System.getenv("MEDHA_KEY_PASSWORD")
val hasReleaseSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.adabala.medha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adabala.medha"
        minSdk = 27
        targetSdk = 34
        versionCode = 10
        versionName = "0.8.3"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                this.keyPassword = keyPassword
            }
        }
    }

    // ---------------------------------------------------------------------
    // Flavours exist for ONE reason: Play Protect blocks sideloaded APKs that
    // declare SMS permissions, and that block is evaluated on the manifest, not
    // on whether the feature is ever used. Keeping SMS out of the default build
    // means the inference service installs cleanly.
    //
    //   core  - inference, memory, RAG, store, notifications. No SMS.
    //   full  - adds the SMS connector. Expect a Play Protect warning.
    // ---------------------------------------------------------------------
    flavorDimensions += "connectors"
    productFlavors {
        create("core") {
            dimension = "connectors"
            versionNameSuffix = "-core"
        }
        create("full") {
            dimension = "connectors"
            versionNameSuffix = "-full"
            // Separate applicationId so both can sit side by side and so a
            // "full" install never silently replaces a clean "core" one.
            applicationIdSuffix = ".full"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Lets a debug and a release build coexist on the same device, so
            // you can compare them without uninstalling.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            // Minification is deliberately OFF. Ktor resolves plugins and
            // kotlinx.serialization resolves serializers reflectively, and
            // LlmEngine probes the LiteRT AAR by reflection for
            // sendMessageAsync. R8 would strip all three without a carefully
            // written keep-rule set, and the failures are runtime-only.
            // Turn this on only alongside a tested proguard-rules.pro.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress += listOf("task", "litertlm", "tflite", "bin")
    }

    // Ktor ships several artifacts that each carry their own metadata files.
    // Without these excludes the merge step fails with duplicate-resource
    // errors that read like an unrelated packaging bug.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/**"
            )
        }
        // Only relevant once the optional embedding dependencies below are
        // enabled: two AARs both ship LiteRT native libraries, and without a
        // pickFirst the merge fails with a duplicate-file error.
        jniLibs {
            pickFirsts += setOf(
                "**/libLiteRt.so",
                "**/libLiteRtClGlAccelerator.so",
                "**/liblitertlm_jni.so",
                "**/libllm_inference_engine_jni.so"
            )
        }
    }

    lint {
        // A lint regression should not block a sideload build, but we still
        // want the report in CI artifacts.
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Pulled in transitively by material, but declared explicitly because the
    // navigation drawer is now load-bearing UI.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Embedded HTTP server (Ktor + CIO)
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")

    // LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // ---------------------------------------------------------------------
    // OPTIONAL: on-device embeddings (vector RAG).
    //
    // Uncomment BOTH lines to enable. AiEdgeEmbedder reaches the SDK by
    // reflection, so the app builds and runs identically without them --
    // retrieval simply stays in lexical mode.
    //
    // Why it is opt-in: localagents-rag pulls in mediapipe tasks-genai, which
    // ships its own copies of the LiteRT native libraries. litertlm-android
    // ships them too. Two AARs contributing libLiteRt.so is a packaging
    // conflict, so enabling this ALSO needs the pickFirst rules in the
    // packaging block above. Verify on a device before relying on it.
    //
    // implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    // implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // ---------------------------------------------------------------------
    // Unit tests (app/src/test — plain JVM, no device/emulator needed).
    //
    // InferenceScheduler's admission-control and timeout behavior is
    // concurrency-dependent and needs a real kotlinx.coroutines dispatcher to
    // mean anything, so it lives here rather than in tools/tests (which only
    // covers the two files with zero framework dependencies). mockk supplies
    // a relaxed Context — the tests only exercise Priority.INTERACTIVE, which
    // never touches BatteryManager/thermal APIs, so nothing Android-specific
    // actually needs to be real.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.11")
}
