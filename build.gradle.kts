buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Align AGP's built-in Kotlin with the JVM and Compose plugins.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.withType<Test>().configureEach {
        maxParallelForks = 2
    }
}
