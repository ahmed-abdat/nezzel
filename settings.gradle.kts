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
    }
}

rootProject.name = "Nezzel"
// Keep Android preferences (including AGP's opt-out state) inside this worktree.
System.setProperty("ANDROID_PREFS_ROOT", file(".gradle-home/android-user").absolutePath)
include(":app", ":core", ":engine")
