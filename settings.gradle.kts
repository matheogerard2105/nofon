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

rootProject.name = "nofon-app"

include(":app")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:tracking")
include(":feature:auth")
include(":feature:home")
include(":feature:social")
include(":feature:group")
include(":feature:exploits")
include(":feature:profile")
