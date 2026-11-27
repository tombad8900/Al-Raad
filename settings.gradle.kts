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
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "Al-Raad"
include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:adblocker")
include(":data")
include(":feature:browser")
include(":feature:player")
include(":feature:library")
