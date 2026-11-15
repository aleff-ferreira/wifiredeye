pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "wifiredeye"

include(":core:model")
include(":core:designsystem")
include(":core:rf-engine")
include(":core:wifi-platform")
include(":cli-runner")
include(":feature:networks")
include(":feature:proximity")
include(":feature:spectrum")
include(":feature:insights")
include(":app")
include(":e2e-tests")
