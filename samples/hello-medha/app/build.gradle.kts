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
    // The only third-party dependency, and only for PDF. PDF stores glyph
    // positions rather than paragraphs, so reconstructing reading order is a
    // real algorithm -- not something to hand-roll. Every other supported
    // format (docx, xlsx, csv, txt) is handled with java.util.zip and the
    // platform's own APIs, so MedhaClient.kt itself stays dependency-free
    // and copyable.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
