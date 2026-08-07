pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiteRT / MediaPipe artifacts live on Google's Maven; add others if your
        // LiteRT-LM build requires a custom repo.
    }
}
rootProject.name = "Medha"
include(":app")
