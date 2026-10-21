pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex(".*android.*")
                includeGroupByRegex(".*google.*")
                includeGroupByRegex(".*kotlin.*")
            }
        }
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

rootProject.name = "Kuguan"
include(":app")