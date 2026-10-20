plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ahmed.savlino"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.ahmed.savlino"
        minSdk = 29
        targetSdk = 35
        // scripts/release.sh passes both; local builds keep the scaffold values.
        versionCode = providers.gradleProperty("nezzel.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("nezzel.versionName").orNull ?: "0.1.0-scaffold"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Foundation verification contract targets arm64, matching the spike.
        ndk { abiFilters += "arm64-v8a" }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // Required by the proven engine: executable payloads extracted to nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // App jniLibs precede AAR inputs: use the pinned 16 KB WebP payload overlay.
            pickFirsts += "lib/arm64-v8a/libffmpeg.zip.so"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".gradle-home/debug.keystore")
        }
        // Release key lives outside the repo; scripts/release.sh supplies it from the macOS Keychain.
        val releaseStore = System.getenv("NEZZEL_RELEASE_STORE")
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("NEZZEL_RELEASE_PASSWORD")
            keyAlias = "nezzel"
            keyPassword = System.getenv("NEZZEL_RELEASE_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

kotlin { jvmToolchain(17) }

// Disposable debug signing only; never create a key in the global Android home.
val generateDebugKey by tasks.registering(Exec::class) {
    val key = rootProject.layout.projectDirectory.file(".gradle-home/debug.keystore")
    outputs.file(key)
    onlyIf { !key.asFile.exists() }
    commandLine(
        "${System.getProperty("java.home")}/bin/keytool", "-genkeypair",
        "-keystore", key.asFile.absolutePath, "-storetype", "PKCS12",
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
    )
}
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(generateDebugKey)
}

val robolectricSdk29 by configurations.creating
val robolectricSdk33 by configurations.creating
val robolectricSdk35 by configurations.creating
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(robolectricSdk29, robolectricSdk33, robolectricSdk35)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}
tasks.withType<Test>().configureEach {
    // Robolectric API35 closes real fixture descriptors via java.io reflection on JDK17.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    dependsOn(prepareRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath)
}

dependencies {
    // Retain the locked compile versions when regenerating locks for the toolchain migration.
    constraints {
        implementation(libs.androidx.core.ktx)
        implementation(libs.coroutines.core)
        testImplementation(libs.okio)
        testImplementation(libs.androidx.tracing)
        androidTestImplementation(libs.androidx.tracing)
    }
    implementation(libs.work.runtime)
    testImplementation(libs.work.testing)
    implementation(libs.okhttp)
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.lifecycle.viewmodel.compose)

    // Resolve test Android jars through Gradle locking + SHA-256 verification; no runtime fetches.
    robolectricSdk29("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    robolectricSdk33("org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // Reuse installed instrumentation pins for Compose's Robolectric runner, offline.
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.espresso.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(testFixtures(project(":engine")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
