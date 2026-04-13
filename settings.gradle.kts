pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "OpenPod"

include(":app")

// Core modules
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:ble")
include(":core:protocol")
include(":core:crypto")
include(":core:ui")
include(":core:audit")
include(":core:testing")

// Feature modules
include(":feature:dashboard")
include(":feature:bolus")
include(":feature:basal")
include(":feature:pairing")
include(":feature:history")
include(":feature:alerts")
include(":feature:settings")
include(":feature:onboarding")
