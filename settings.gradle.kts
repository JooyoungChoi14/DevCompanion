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
        // GeckoView is published to Mozilla's Maven repository
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "DevCompanion"
include(":app")