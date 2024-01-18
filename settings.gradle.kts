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

rootProject.name = "OpenFeature"
include(":android")
include(":kotlinxserialization")

includeBuild(".") {
    dependencySubstitution {
        substitute(module("dev.openfeature:android-sdk")).using(project(":android"))
    }
}