plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hellomedha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.hellomedha"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Top level, NOT inside android { } -- `kotlin` is a project extension, not
// an Android DSL block, and nesting it there does not resolve. Matches the
// placement in the root project's app/build.gradle.kts.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Deliberately minimal. The point of the sample is that talking to Medha
    // needs no HTTP library, no JSON library, and no Medha dependency --
    // HttpURLConnection and org.json are both in the platform.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // Material 3 for the chat UI. Still no HTTP or JSON library -- the point
    // that talking to Medha needs neither is unchanged.
    implementation("com.google.android.material:material:1.12.0")
}
