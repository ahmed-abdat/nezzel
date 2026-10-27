pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor ships only on JitPack: nothing else resolves there, and nothing NewPipe resolves elsewhere.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\.TeamNewPipe(\\..*)?") }
        }
    }
}

rootProject.name = "Nezzel"
// Keep Android preferences (including AGP's opt-out state) inside this worktree.
System.setProperty("ANDROID_PREFS_ROOT", file(".gradle-home/android-user").absolutePath)
include(":app", ":core", ":engine", ":baselineprofile")
