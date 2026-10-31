import java.security.MessageDigest

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

// res/raw/ytdlp overrides the wrapper's 2025.11.12 copy (YtdlpRelease.BUNDLED_TAG); a different file fails the build.
val verifyBundledYtdlp = tasks.register("verifyBundledYtdlp") {
    val pinned = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3"
    val bundled = layout.projectDirectory.file("src/main/res/raw/ytdlp")
    inputs.file(bundled)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(bundled.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == pinned) { "res/raw/ytdlp SHA-256 $digest is not the pinned $pinned" }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledYtdlp) }

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

// Push suite. -PnezzelNightly=true runs the wall-clock transport and timeout tier.
// Fast contract and parser cases in these classes stay on the push suite.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("nezzelNightly").orNull != "true") {
        filter.excludeTestsMatching("*.EngineTimeoutRecoveryTest") // stall and watchdog recovery
        filter.excludeTestsMatching("*.InstagramCarouselParallelTest") // slept parallel photo timing
        filter.excludeTestsMatching("*.TikTokParallelPhotosTest") // slept parallel photo timing
        filter.excludeTestsMatching("*.ProcessTreeTest") // process-tree signals, not user data or a parser
        filter.excludeTestsMatching("*.MediaHttpClientTest.dnsWholeDeadline*") // DNS deadline ignores interruption
        filter.excludeTestsMatching("*.MediaHttpClientTest.wholeOperationDeadline*") // whole-operation deadline
        filter.excludeTestsMatching("*.MediaHttpClientTest.readTimeout*") // stalled-header read timeout
        filter.excludeTestsMatching("*.MediaHttpImageTransferTest.saveFiveSecondIdleTimeout*") // 5s idle body
        filter.excludeTestsMatching("*.YouTubeOEmbedTransportTest.stalledOembed*") // stalled oEmbed; metadata cases stay
        filter.excludeTestsMatching("*.XInspectionTest.syndicationTransportFailures*") // transport phase; page parsing stays
        filter.excludeTestsMatching("*.NpeInspectTest.npeTimeout*") // NPE timeout versus the yt-dlp budget
        filter.excludeTestsMatching("*.NpeInspectTest.timedOutWorker*") // timed-out worker holds a slot
        filter.excludeTestsMatching("*.NpeInspectTest.joinerWaits*") // joiner waits out a slow owner
        filter.excludeTestsMatching("*.NpeInspectTest.lateUnreachable*") // grace-window unreachable verdict
        filter.excludeTestsMatching("*.NpeDownloadTest.timeoutAndOccupied*") // occupied fast slot uses the full budget
        filter.excludeTestsMatching("*.NpeDownloaderTest.operationTimeout*") // operation timeout bounds later calls
        filter.excludeTestsMatching("*.NpeFastSourceTest.budgetSpent*") // budget spent while connecting
        filter.excludeTestsMatching("*.YtdlpUpdateStageTest.inspectionAfterTheCallerStoppedWaiting*") // waits out the install
        filter.excludeTestsMatching("*.Mp3EncoderTest.waitingForAnotherJobsEncoders*") // stall window while permits are held
        filter.excludeTestsMatching("*.Mp3EncoderTest.aSlowSinglePass*") // stall window while a pass keeps writing
    }
}
