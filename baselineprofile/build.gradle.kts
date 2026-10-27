plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.ahmed.savlino.baselineprofile"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Non-rooted devices collect profiles from API 33; the phone runs Android 16.
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".gradle-home/debug.keystore")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    targetProjectPath = ":app"
}

kotlin { jvmToolchain(17) }

// The phone attached over USB; no Gradle-managed emulator (AGENTS.md hard rule 1).
baselineProfile {
    useConnectedDevices = true
}

// Neither AGP nor the baselineprofile plugin passes the tested app's id; Target.kt reads it and refuses non-.demo ids.
androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId },
        )
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.benchmark.macro.junit4)
}

// Same disposable debug key as :app (created there, never in the global Android home).
tasks.matching { it.name.startsWith("validateSigning") }.configureEach {
    dependsOn(":app:generateDebugKey")
}
