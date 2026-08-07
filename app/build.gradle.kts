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

android {
    namespace = "com.example.litertservice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.litertservice"
        minSdk = 27
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.0"
    }

    buildTypes {
        debug { isDebuggable = true }
        release { isMinifyEnabled = false }
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
}
