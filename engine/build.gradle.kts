plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ahmed.savlino.engine"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    testFixtures {
        enable = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    constraints {
        implementation(libs.okio)
        // NewPipeExtractor's runtime graph, pinned to the hash-verified versions in THIRD_PARTY_NOTICES.md.
        implementation(libs.rhino)
        implementation(libs.rhino.engine)
        implementation(libs.protobuf.javalite)
        // youtubedl-android 0.18.1 brings Jackson 2.11.1, commons-io 2.5 and commons-compress 1.12, all with
        // published advisories. Jackson parses yt-dlp and site JSON; the other two unpack the bundled payloads.
        implementation(libs.jackson.core)
        implementation(libs.commons.io)
        implementation(libs.commons.compress)
    }
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.jackson.databind)
    implementation(libs.jsoup)
    implementation(libs.newpipe.extractor)
    implementation(libs.coroutines.core)
    // Align the engine runtime with the already adopted app AndroidX graph.
    runtimeOnly(libs.androidx.appcompat)
    runtimeOnly(libs.androidx.core.ktx)
    testFixturesImplementation(libs.coroutines.core)
    testImplementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":engine")))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
