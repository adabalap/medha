// Standalone on purpose: this sample is NOT a module of the Medha build.
//
// Adding it to Medha's own settings.gradle.kts would mean a broken sample
// fails Medha's build at configuration time, and CI would start compiling a
// demo app on every push to the real one. Open this folder directly in
// Android Studio, or build it with its own gradlew.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "hello-medha"
include(":app")
