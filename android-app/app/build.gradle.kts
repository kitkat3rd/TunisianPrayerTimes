import org.gradle.api.GradleException
import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.util.Properties

val unsignedRelease = providers.gradleProperty("unsignedRelease")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (!unsignedRelease && keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

fun requireKeystoreProperty(name: String): String {
    return keystoreProperties.getProperty(name)
        ?: throw GradleException("Missing '$name' in ${keystorePropertiesFile.name}.")
}

if (isReleaseTask && !unsignedRelease && !keystorePropertiesFile.exists()) {
    throw GradleException("Missing ${keystorePropertiesFile.path}. Create it before running release builds.")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    // Bundles data/prayer-formula and data/official-islamic-dates as assets.
    id("tunisianprayertimes.bundled-data")
    // Ships the Quran's recitations and page scans as Play Asset Delivery packs.
    id("tunisianprayertimes.quran-assets")
}

android {
    namespace = "com.tunisianprayertimes"
    compileSdk = 36


    signingConfigs {
        create("release") {
            if (!unsignedRelease && keystorePropertiesFile.exists()) {
                storeFile = file(requireKeystoreProperty("storeFile"))
                storePassword = requireKeystoreProperty("storePassword")
                keyAlias = requireKeystoreProperty("keyAlias")
                keyPassword = requireKeystoreProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.tunisianprayertimes"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 155
        versionName = "2.144"
        
        androidResources {
            localeFilters += "ar"
        }
        
        ndk {
            // Target the most common architectures for debug to avoid split issues
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        @Suppress("DEPRECATION")
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // MediaPlayer streams recitations that a build bundles directly through AssetFileDescriptor.
        noCompress += "mp3"
        // The mushaf book's fonts are tens of megabytes each: stored as they are, they are mapped, not inflated.
        noCompress += "otf"
    }

    // The mushaf typeset as a book in the print's own type (the book build of scripts/mushaf_typeset), for trying it on
    // a phone before it has a pack of its own: -PquranFonts=<folder holding quran/book: plates.json, book_N.otf and
    // pages/page_NNN.bin, as the book build's book_pack.py writes it> bundles it into a debug build.
    (project.findProperty("quranFonts") as String?)?.let { folder ->
        sourceSets.getByName("debug").assets.srcDir(file(folder))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Prayer times are instants in the place's zone (plan 6.6); the tests read Tunisian times as the
            // phone's clock, so the phone is in Tunisia unless a test sets another zone (CI runs in UTC).
            // One Robolectric sandbox per SDK level (26 to 35) outgrows Gradle's default 512 MB: the suite stopped
            // with an OutOfMemoryError before its last classes, already at 03647f2fc.
            all {
                it.systemProperty("user.timezone", "Africa/Tunis")
                it.maxHeapSize = "2g"
            }
        }
    }
}

abstract class GenerateTafsirAssets : Exec() {
    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty
}

// Private previews and cleared release books have distinct generated source directories.
// A checkout without local texts can still build debug or an explicitly text-free APK.
val tafsirRepo = rootProject.projectDir.parentFile
val tafsirScripts = tafsirRepo.resolve("scripts/quran_tafsir")
val tafsirStage = tafsirRepo.resolve("scripts/quran-data/tafsir-stage.json")
val tafsirPython = providers.gradleProperty("tafsirPython")
    .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
val researchDirectory = tafsirRepo.resolve("work/tafsir-research")
val clearedAssets = providers.gradleProperty("tafsirReleaseAssets").orElse(providers.environmentVariable("TAFSIR_RELEASE_ASSETS"))
    .orElse(tafsirRepo.resolve("work/tafsir-release/compiled/assets").absolutePath)
val withoutTafsir = providers.gradleProperty("withoutTafsir").map { it.toBooleanStrict() }.orElse(false)
val localCatalog = rootProject.file(clearedAssets.get()).resolve("quran/tafsir/catalog.json")
val tafsirStageMetadata = JsonSlurper().parse(tafsirStage) as Map<*, *>
val hasTafsirStage = (tafsirStageMetadata["books"] as List<*>).isNotEmpty()
val hasTafsirPack = (tafsirStageMetadata["files"] as Map<*, *>).containsKey("quran_tafsir_ashur/assets/quran/tafsir/ashur.db")
val approvedDebug = providers.gradleProperty("tafsirApprovedDebug").map { it.toBooleanStrict() }
    .orElse(hasTafsirStage && localCatalog.isFile)
// APKs installed outside Play can read the same approved database without a first network request.
// Asset delivery bundles carry that database in the on-demand module instead.
val releaseCommands = gradle.startParameter.taskNames.map { it.substringAfterLast(':').lowercase() }
val wantsReleaseBundle = releaseCommands.any { it == "bundle" || (it.contains("bundle") && it.contains("release")) }
val wantsReleaseApk = releaseCommands.any { it in setOf("assemble", "assemblerelease", "installrelease", "packagerelease", "verifyreleasetafsirapk", "build") }
val requestedStandalone = providers.gradleProperty("tafsirStandalone").map { it.toBooleanStrict() }
val standaloneTafsir = requestedStandalone.orElse(!wantsReleaseBundle)
if (hasTafsirPack && !withoutTafsir.get() && wantsReleaseBundle && (standaloneTafsir.get() || (wantsReleaseApk && !requestedStandalone.isPresent))) {
    throw GradleException("Build the standalone APK with assembleRelease and the on-demand Play bundle with bundleRelease in separate Gradle invocations.")
}

androidComponents.onVariants { variant ->
    if (variant.buildType == "debug" && !approvedDebug.get() && researchDirectory.isDirectory) {
        val preview = tasks.register<GenerateTafsirAssets>("prepareDebugTafsirPreview") {
            assetsDirectory.set(layout.buildDirectory.dir("generated/tafsirPreview/assets"))
            workingDir(tafsirRepo)
            inputs.files(fileTree(tafsirScripts) { include("preview*.py") })
            inputs.file(tafsirRepo.resolve("scripts/mushaf_text/mushaf_text.py"))
            inputs.files(fileTree(researchDirectory) {
                include(
                    "full/ar-tafsir-muyassar/*.json", "full/ar-tafsir-al-mukhtasar/*.json",
                    "full/ar-tafseer-tahrir-al-tanwir/*.json", "editions.json", "tafsir_map/out/*.json",
                    "verify/kufi/*.json", "verify/oi/*.txt", "px/data3.json", "dl_q1/ashur1.json",
                    "tafsir_samples/ar-tafseer-tahrir-al-tanwir.*.json", "verify/ia_heads/**", "chk_rum/intro/**",
                    "round2/intro/**", "round2/gharib/dl_ta/almuyassar-ghareeb/*.json",
                    "round2/gharib/out/*.json", "round3/kfgqpc/*.json", "round2/readings/out/raw_diffs4.json",
                )
            })
            inputs.file(layout.projectDirectory.file("src/main/assets/quran/index.json"))
            commandLine(tafsirPython.get(), tafsirScripts.resolve("preview.py"), "--output", assetsDirectory.get().asFile)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(preview, GenerateTafsirAssets::assetsDirectory)
    }
    val checkedDebug = variant.buildType == "debug" && approvedDebug.get()
    if (variant.buildType == "release" || checkedDebug) {
        val label = if (checkedDebug) "Debug" else "Release"
        val standalone = checkedDebug || standaloneTafsir.get()
        val empty = !checkedDebug && withoutTafsir.get()
        val tool = tafsirScripts.resolve("packaging.py")
        val verifier = listOf(tafsirPython.get(), tool, "--stage", tafsirStage) +
            (if (empty) listOf("--without-content") else emptyList()) +
            (if (standalone) listOf("--standalone") else emptyList())
        val prepare = tasks.register<GenerateTafsirAssets>("prepare${label}TafsirAssets") {
            if (checkedDebug && !hasTafsirStage) throw GradleException("Approved debug tafsir requires a nonempty committed stage.")
            assetsDirectory.set(layout.buildDirectory.dir("generated/tafsir${label}/assets"))
            inputs.files(tool, tafsirStage)
            inputs.property("standalone", standalone)
            inputs.property("withoutContent", empty)
            inputs.property("checkPlayModule", !checkedDebug && wantsReleaseBundle)
            workingDir(tafsirRepo)
            commandLine(verifier + listOf("prepare", "--output", assetsDirectory.get().asFile))
            if (!empty) {
                val source = rootProject.file(clearedAssets.get())
                inputs.files(fileTree(source.resolve("quran/tafsir")))
                if (standalone) inputs.files(fileTree(source.parentFile.resolve("media/tafsir")) { include("ashur.db") })
                args("--source", source)
                if (!checkedDebug && wantsReleaseBundle) {
                    val packAssets = rootProject.file("quran-packs/quran_tafsir_ashur/src/main/assets/quran/tafsir")
                    inputs.files(fileTree(packAssets) { include("ashur.db") })
                    args("--play-database", packAssets.resolve("ashur.db"))
                }
            }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(prepare, GenerateTafsirAssets::assetsDirectory)

        val apk = variant.artifacts.get(SingleArtifact.APK)
        val checkApk = tasks.register<Exec>("verify${label}TafsirApk") {
            inputs.files(apk, tool, tafsirStage)
            commandLine(verifier + listOf("verify", apk.get().asFile))
        }
        tasks.matching { it.name == "assemble${label}" || it.name == "package${label}" }.configureEach { finalizedBy(checkApk) }
        if (!checkedDebug) {
            val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
            val checkBundle = tasks.register<Exec>("verifyReleaseTafsirBundle") {
                inputs.files(bundle, tool, tafsirStage)
                commandLine(verifier + listOf("verify", bundle.get().asFile))
            }
            tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(checkBundle) }
        }
    }
}

dependencies {
    implementation("com.tunisianprayertimes:shared")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Quran packs for Play installs; other installs download them from the quran-cdn Worker.
    implementation("com.google.android.play:asset-delivery-ktx:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("net.sf.geographiclib:GeographicLib-Java:2.1")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // Instrumented tests (Compose)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-analytics")
}
