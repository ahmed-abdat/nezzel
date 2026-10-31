import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

@CacheableTask
abstract class GenerateWhatsNewAssets : DefaultTask() {
    @get:Input abstract val versionCode: Property<Int>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val englishChangelogs: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val arabicChangelogs: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frenchChangelogs: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile.resolve("whats-new")
        destination.mkdirs()
        val languages = listOf("en" to englishChangelogs, "ar" to arabicChangelogs).map { (language, directory) ->
            language to directory.get().asFile
        }
        // One version for both languages: the exact build's notes, else the newest version both have. A newer note in
        // only one language would ship two different versions and the sheet would reject both.
        fun versions(dir: java.io.File) = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "txt" }.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.toSet()
        val common = languages.map { versions(it.second) }.reduce { a, b -> a intersect b }
        val version = versionCode.get().takeIf { it in common } ?: common.maxOrNull()
        for ((language, changelogs) in languages) {
            val target = destination.resolve("$language.txt")
            if (version != null) changelogs.resolve("$version.txt").copyTo(target, overwrite = true) else target.delete()
        }
        val french = version?.let { frenchChangelogs.get().asFile.resolve("$it.txt") }?.takeIf { it.isFile }
        val target = destination.resolve("fr.txt")
        if (french != null) french.copyTo(target, overwrite = true) else target.delete()
    }
}

android {
    namespace = "dev.ahmed.savlino"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // -Pnezzel.applicationIdSuffix=.demo installs a separate test copy beside the real app.
        applicationId = "dev.ahmed.savlino" + (providers.gradleProperty("nezzel.applicationIdSuffix").orNull ?: "")
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

    lint {
        // Compose 1.12 / lifecycle lint rules new since BOM 2026.09: report, don't fail (UI code unchanged).
        warning += setOf("LocalContextGetResourceValueCall", "ViewModelConstructorInComposable")
    }

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
        // NewPipeExtractor calls Java 10+ stream APIs (Collectors.toUnmodifiableList) that API 29-32 lack.
        isCoreLibraryDesugaringEnabled = true
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
        // Baseline profile generation installs these, and the test run uninstalls them afterwards: a fixed .demo
        // suffix keeps the real app and its data out of reach even when no -P suffix is passed. The baselineprofile
        // plugin reuses build types the app declares under these names.
        for (name in listOf("nonMinifiedRelease", "benchmarkRelease")) create(name) {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
            if (name == "nonMinifiedRelease") {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }
}

baselineProfile {
    // Profiles are generated on a device by hand (:app:generateReleaseBaselineProfile), never during a build.
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

androidComponents.onVariants { variant ->
    if (variant.buildType in setOf("nonMinifiedRelease", "benchmarkRelease")) {
        check(variant.applicationId.get().endsWith(".demo")) { "${variant.name} must install as the .demo copy" }
    }
    val notes = tasks.register<GenerateWhatsNewAssets>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}WhatsNewAssets") {
        versionCode.set(variant.outputs.single().versionCode)
        englishChangelogs.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android/en-US/changelogs"))
        arabicChangelogs.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android/ar/changelogs"))
        frenchChangelogs.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android/fr-FR/changelogs"))
        outputDirectory.set(layout.buildDirectory.dir("generated/whats-new/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(notes, GenerateWhatsNewAssets::outputDirectory)
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
tasks.matching { it.name in setOf("validateSigningDebug", "validateSigningNonMinifiedRelease", "validateSigningBenchmarkRelease") }.configureEach {
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
    // One JVM runs ~2000 Robolectric tests over three SDKs; the 512 MB default runs out near the end
    maxHeapSize = "2g"
    dependsOn(prepareRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath)
    // PNG generators boot Robolectric before their environment guard can skip them.
    // Opt in with -PnezzelCapture=true and NEZZEL_CAPTURE_DIR. A new *CaptureTest class is excluded without edits here.
    if (providers.gradleProperty("nezzelCapture").orNull != "true") {
        filter.excludeTestsMatching("*CaptureTest")
        filter.excludeTestsMatching("*CaptureTest$*")
        filter.excludeTestsMatching("*.HomeRedesignTest.capture*")
        filter.excludeTestsMatching("*.HomeStackTest.ytmStrips")
        filter.excludeTestsMatching("*.HomeStackTest.oneSurface")
        filter.excludeTestsMatching("*.HomeStackTest.captures")
        filter.excludeTestsMatching("*.MultiLinkSheetTest.captures")
    }
    // Push suite. -PnezzelNightly=true runs this slow tier plus the recomposition budgets.
    // Each line is one class or method that is not the only home of a permission, integrity, parser, RTL, or contract rule.
    if (providers.gradleProperty("nezzelNightly").orNull != "true") {
        filter.excludeTestsMatching("*.DownloadsTest.finishOnSettingsHonorsAccessibilityTimeoutAndShareUsesPublishedUri") // 7s snackbar; URI sharing stays in FileShareTest
        filter.excludeTestsMatching("*.DownloadCoordinatorTest.unfinishedOldWorkTimeoutPreservesFailedJournalAndStaging") // 5s deadline; old-worker journal barrier stays in retryWaitsForOldWorkerToFinishFailureBeforeChangingJournal
        filter.excludeTestsMatching("*.DownloadCoordinatorTest.hungOrphanCleanupCannotBlockStartupOrDownloadAdmission") // 3s cleanup deadline; startup recovery stays in startupRecoversInterruptedRowsBeforeReschedulingQueuedWork
        filter.excludeTestsMatching("*.SaveDestinationTest.hungOrphanQueryTimesOutAndCannotDeleteAfterStartup") // 3s provider deadline; receipt reuse stays in orphanSweepPreserves* methods
        filter.excludeTestsMatching("*.NotificationPermissionFlowTest.api35RequestsPermissionBeforeDownload") // API35 UI repeat; API33 permission/denial and pre33 cases stay on push
        filter.excludeTestsMatching("dev.ahmed.savlino.ui.nav.TabSwitchCostTest") // recomposition budget
        filter.excludeTestsMatching("dev.ahmed.savlino.ui.nav.ProgressTickCostTest") // recomposition budget
        filter.excludeTestsMatching("*.ThumbnailLoaderTest") // 10s whole-load deadline
        filter.excludeTestsMatching("*.HomeInPlaceTest") // slot transitions; intake privacy stays in EntryFlowTest, Arabic layout in NavigationLayoutTest
        filter.excludeTestsMatching("*.ActivityEntryTest") // activity recreation, not a permission or integrity rule
        filter.excludeTestsMatching("*.HomeFirstRunFlipTest") // hero swap; tour RTL stays in OnboardingTest
        filter.excludeTestsMatching("*.EngineUpdateFlowTest") // maintenance deferral while an inspection is running
        filter.excludeTestsMatching("*.LibraryLoadTest") // library load frames
        filter.excludeTestsMatching("*.StatusesScreenTest") // statuses UI, no locale or permission rule
        filter.excludeTestsMatching("*.AutomaticIntakeTest") // intake UI; link parsing stays in core IntakeTest
        filter.excludeTestsMatching("*.LibraryPolishUiTest") // library polish UI
        filter.excludeTestsMatching("*.EngineMaintenanceIntegrationTest") // maintenance integration
        filter.excludeTestsMatching("*.MediaHttpCompatibilityTest") // API 33 client construction
        filter.excludeTestsMatching("*.ViewerPullTest") // pull gesture; Arabic layout stays in NavigationLayoutTest
        filter.excludeTestsMatching("*.OnboardingStartupTest") // startup maintenance deferral
        filter.excludeTestsMatching("*.ShortcutEntryTest") // shortcut entry
        filter.excludeTestsMatching("*.ShortcutMaintenanceTest") // shortcut maintenance
        filter.excludeTestsMatching("*.LibraryStateTest") // library list state
        filter.excludeTestsMatching("*.EngineMaintenanceTest") // maintenance state
        filter.excludeTestsMatching("*.LinkPreviewsTest") // preview rows; cache integrity stays in PreviewCacheStorageTest
        filter.excludeTestsMatching("*.SilentUpdateIntegrationTest") // silent update; schedule rules stay in AppUpdatesTest
        filter.excludeTestsMatching("*.TabContentTest") // tab content
        filter.excludeTestsMatching("*.CopyLinkUiTest") // copy-link UI
        filter.excludeTestsMatching("*.ViewerHostTest") // viewer host
        filter.excludeTestsMatching("*.StartupMaintenanceTest") // startup maintenance
        filter.excludeTestsMatching("*.MediaViewerTest") // viewer shell; playback stays in PlaybackCoordinatorTest, Arabic layout in NavigationLayoutTest
        filter.excludeTestsMatching("*.ImageInspectionFlowTest") // inspection flow; pixel parsing stays in MediaImageDecoderTest
        filter.excludeTestsMatching("*.EngineUpdatePreemptionTest") // update preemption
        filter.excludeTestsMatching("*.InstagramPostFlowTest") // post flow; Instagram parsing stays in the engine
        filter.excludeTestsMatching("*.LinkViewModelRaceTest") // view-model races
        filter.excludeTestsMatching("*.AudioPlaybackServiceTest") // playback service
        filter.excludeTestsMatching("*.FacebookVideoHandoffTest") // video handoff; Facebook parsing stays in the engine
        filter.excludeTestsMatching("*.SavedListTopTest") // saved-list scroll
        filter.excludeTestsMatching("*.ViewInputTest") // view input
        filter.excludeTestsMatching("*.ThumbnailCacheTest") // thumbnail cache
        filter.excludeTestsMatching("*.PauseRowTest") // pause row
        filter.excludeTestsMatching("*.UnreportedDirectMp4Test") // direct mp4 UI; mapping stays in UnreportedDirectMp4MappingTest
        filter.excludeTestsMatching("*.ShareSheetPhotoFlowTest") // photo share flow; Arabic sheet stays in ShareSheetFlowTest
        filter.excludeTestsMatching("*.StatusesStateTest") // statuses state
        filter.excludeTestsMatching("*.ViewerControlsTest") // virtual-time control UI; playback integrity/privacy stay in Playback*Test, RTL in NavigationLayoutTest
        filter.excludeTestsMatching("*.UpdateScreenTest") // update UI/lifecycle; installer permission contract stays in UpdateInstallerTest, state in AppUpdatesTest
        filter.excludeTestsMatching("*.SettingsScreenTest") // settings controls; preferences stay in LocaleThemeTest/TipsPolicyTest, French rendering in DownloadsTabsTest
        filter.excludeTestsMatching("*.HomeSemanticsTest") // intake UI; clipboard privacy stays in EntryFlowTest/ClipboardEntryTest, multi-link consent in MultiLinkIntakeTest
        filter.excludeTestsMatching("*.HomeStackTest") // home/player layout; locale rendering stays in NavigationLayoutTest/DownloadsTabsTest
        filter.excludeTestsMatching("*.ResponsiveRowsTest") // row geometry; queue ordering stays in DownloadCoordinatorTest, locale rendering in NavigationLayoutTest
        filter.excludeTestsMatching("*.MiniPlayerTest") // player gestures/layout; playback persistence/privacy stay in PlaybackResumeStoreTest/PlaybackPrivacyTest
        filter.excludeTestsMatching("*.SelectionReturnTest") // returned radio highlight; exact format selection stays in SelectionFlowTest
        filter.excludeTestsMatching("*.SelectionSemanticsTest") // option semantics; explicit confirmation stays in SelectionFlowTest, preview integrity in PreviewCacheStorageTest
        filter.excludeTestsMatching("*.JobCardUxTest") // job card layout; battery policy stays in BatterySettingsTest, locale rendering in NavigationLayoutTest
        filter.excludeTestsMatching("*.DownloadsScreenWarningsTest") // warning rows; late probe results stay in DownloadCoordinatorTest, quality selection in BetterQualityFlowTest
        filter.excludeTestsMatching("*.PlaylistBatchCardTest") // batch card layout; durable batch rules stay in BatchCoordinatorTest/BatchStoreTest
        filter.excludeTestsMatching("*.TabBarLargeTextTest") // tab geometry; Arabic/French large-text rendering stays in NavigationLayoutTest/DownloadsTabsTest
        filter.excludeTestsMatching("*.ImageOptionsUiTest") // image copy/layout; returned-image facts stay in ImageOptionsMappingTest, parsers in the engine
        filter.excludeTestsMatching("*.WhatsNewSheetTest") // release-note surfaces; retained notes stay in AppUpdatesTest, bundled content in BundledWhatsNewTest
        filter.excludeTestsMatching("*.DownloadsFailuresTest") // failure rows; retry eligibility stays in DownloadCoordinatorTest/BatchCoordinatorTest
        filter.excludeTestsMatching("*.DownloadsLayoutTest") // loading/empty layout; saved-file integrity stays in SavedMediaRepositoryTest, RTL in NavigationLayoutTest
        filter.excludeTestsMatching("*.ViewerAudioTest") // audio layout/screen flags; playback lifecycle stays in PlaybackCoordinatorTest, RTL in NavigationLayoutTest
        filter.excludeTestsMatching("*.BatchHeaderUiTest") // batch header layout; batch state stays in BatchCoordinatorTest/BatchStoreTest
        filter.excludeTestsMatching("*.SharedAxisTest") // navigation animation; locale layout stays in NavigationLayoutTest
        filter.excludeTestsMatching("*.SavedMomentTest") // date labels; date/locale rules stay in ShortDateTest/LocaleThemeTest
        filter.excludeTestsMatching("*.FillRowTest") // row fill/layout; progress honesty stays in ProgressHonestyTest, RTL in NavigationLayoutTest
        filter.excludeTestsMatching("*.FilterRowTest") // chip geometry; Arabic large-text rendering stays in NavigationLayoutTest
        filter.excludeTestsMatching("*.ThumbRowTest") // thumbnail geometry; artwork integrity stays in PreviewCacheStorageTest
        filter.excludeTestsMatching("*.AboutSectionTest") // about/licence surfaces; bundled licence loading stays in LicenceLoaderTest, locale rendering in NavigationLayoutTest
        filter.excludeTestsMatching("*.OptionsFooterTest") // footer placement; Arabic large-text rendering stays in NavigationLayoutTest
        filter.excludeTestsMatching("*.DownloadsTabIndicatorTest") // tab animation; tab state and French rendering stay in DownloadsTabsTest
        // Layout fixtures. Capture shots of this class stay available with -PnezzelCapture=true.
        if (providers.gradleProperty("nezzelCapture").orNull != "true") {
            filter.excludeTestsMatching("*.HomeRedesignTest")
        }
    }
}

dependencies {
    // Retain the locked compile versions when regenerating locks for the toolchain migration.
    constraints {
        implementation(libs.androidx.core.ktx)
        implementation(libs.coroutines.core)
        testImplementation(libs.okio)
        testImplementation(libs.androidx.tracing)
    }
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.work.runtime)
    testImplementation(libs.work.testing)
    implementation(libs.okhttp)
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.lifecycle.viewmodel.compose)
    // Local saved-file playback only (owner 2026-10-26): no DASH/HLS/RTSP, Cast or decoder extensions.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    // Installs the bundled baseline profile on sideloaded installs, where no store does it.
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

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
}
