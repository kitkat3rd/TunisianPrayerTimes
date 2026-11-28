import org.gradle.api.GradleException
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.net.URI
import java.security.MessageDigest
import java.security.DigestInputStream
import java.io.OutputStream

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
    // Google Play delivers Quran page scans, selected recitations and the five tafsir books on demand.
    id("tunisianprayertimes.quran-assets")
}

// A release is production unless the Play beta artifact was explicitly requested.
val mosqueBeta = providers.gradleProperty("mosquePlayBeta").map(String::toBooleanStrict).orElse(false)
val reportOrigin = providers.gradleProperty("mosqueReportsOrigin").orElse("https://mosque-silencing-reports.pages.dev").get().trimEnd('/')
val devReportOrigin = if (providers.gradleProperty("mosqueDevLocal").orNull == "true") "http://127.0.0.1:8788" else reportOrigin
run {
    val uri = URI.create(reportOrigin)
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
        uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "mosqueReportsOrigin must be an HTTPS origin" }
}
val catalogueDigest = MessageDigest.getInstance("SHA-256").let { digest ->
    project.file("src/main/assets/mosques/tunisia-mosques.geojson").inputStream().use { input ->
        DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) }
    }
    digest.digest().take(8).joinToString("") { "%02x".format(it) }
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
        buildConfigField("String", "MOSQUE_CATALOG_VERSION", "\"" + catalogueDigest + "\"")
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
            buildConfigField("String", "MOSQUE_REPORT_CHANNEL", "\"dev\"")
            buildConfigField("String", "MOSQUE_REPORT_ORIGIN", "\"" + devReportOrigin + "\"")
        }
        release {
            buildConfigField("String", "MOSQUE_REPORT_CHANNEL", if (mosqueBeta.get()) "\"beta\"" else "\"production\"")
            buildConfigField("String", "MOSQUE_REPORT_ORIGIN", "\"" + reportOrigin + "\"")
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
        buildConfig = true
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

// Every base contains the same public catalog. Play supplies on-demand asset packs; the APK uses the CDN.
val tafsirRepo = rootProject.projectDir.parentFile
val tafsirTool = tafsirRepo.resolve("scripts/quran_tafsir/packaging.py")
val tafsirStage = tafsirRepo.resolve("scripts/quran-data/tafsir-stage.json")
val tafsirCatalog = tafsirRepo.resolve("scripts/quran-data/tafsir-catalog.json")
val quranPackCatalog = layout.projectDirectory.file("src/main/assets/quran/packs.json")
val tafsirPython = providers.gradleProperty("tafsirPython")
    .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")

val prepareTafsirCatalog = tasks.register<GenerateTafsirAssets>("prepareTafsirCatalog") {
    assetsDirectory.set(layout.buildDirectory.dir("generated/tafsirCatalog/assets"))
    inputs.files(tafsirTool, tafsirStage, tafsirCatalog, quranPackCatalog)
    commandLine(tafsirPython.get(), tafsirTool, "--stage", tafsirStage, "prepare",
        "--catalog", tafsirCatalog, "--packs", quranPackCatalog.asFile, "--output", assetsDirectory.get().asFile)
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareTafsirCatalog, GenerateTafsirAssets::assetsDirectory)
    val label = variant.name.replaceFirstChar { it.uppercaseChar() }
    val apk = variant.artifacts.get(SingleArtifact.APK)
    val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
    val checkApk = tasks.register<Exec>("verify${label}TafsirApk") {
        inputs.files(apk, tafsirTool, tafsirStage, quranPackCatalog)
        commandLine(tafsirPython.get(), tafsirTool, "--stage", tafsirStage, "verify", "--packs", quranPackCatalog.asFile, apk.get().asFile)
    }
    val checkBundle = tasks.register<Exec>("verify${label}TafsirBundle") {
        inputs.files(bundle, tafsirTool, tafsirStage, quranPackCatalog)
        commandLine(tafsirPython.get(), tafsirTool, "--stage", tafsirStage, "verify", "--packs", quranPackCatalog.asFile, bundle.get().asFile)
    }
    tasks.matching { it.name == "assemble${label}" || it.name == "package${label}" }.configureEach { finalizedBy(checkApk) }
    tasks.matching { it.name == "bundle${label}" }.configureEach { finalizedBy(checkBundle) }
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
