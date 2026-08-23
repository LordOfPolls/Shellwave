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

// No toolchain provisioning here on purpose: no foojay resolver plugin, and no
// gradle/gradle-daemon-jvm.properties. A build that fetches its own JDK from api.foojay.io
// part-way through cannot run on F-Droid's build server, which brings its own JDK and expects
// the build to use it. Gradle runs on whatever JVM launches it; each module pins its own
// source/target compatibility, which is what actually decides the bytecode. `./gradlew
// updateDaemonJvm` re-creates that properties file - don't run it.

rootProject.name = "Shellwave"
include(":app")
include(":terminal-core")
