import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.galleryslim"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galleryslim"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // Test installs on a nearly full phone: keep only the 64-bit ARM libraries (S24 and almost every
            // current phone). Play Store builds (AAB) are split per device by Google anyway.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        // Small test build for installing on a nearly full phone: shrunk like release, signed with the debug key.
        create("phone") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // JVM tests run the real share server; Android logging calls just return defaults there.
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures {
        compose = true
    }
}

// GallerySlim Receiver downloads (served by the phone's page at /receiver/<name>): copied at build time from
// dist/receiver into a generated assets dir, never committed under src/. Missing dist/receiver → no download offered.
abstract class BundleReceiverDownloads : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val distDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val dist = distDir.get().asFile
        val out = outputDir.get().asFile.resolve("receiver")
        out.deleteRecursively(); out.mkdirs()
        val bundled = mutableListOf<File>()
        // Windows: one .exe zipped here (fixed timestamps, so the archive and its checksum are reproducible).
        val exe = dist.resolve("windows-amd64/GallerySlim-Receiver.exe")
        if (exe.isFile) {
            val zip = out.resolve("GallerySlim-Receiver-windows-amd64.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("GallerySlim-Receiver.exe").apply { time = 318_211_200_000L })
                exe.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            bundled += zip
        }
        listOf("GallerySlim-Receiver-macos-arm64.zip", "GallerySlim-Receiver-macos-amd64.zip", "GallerySlim-Receiver-linux-amd64.tar.gz")
            .map { dist.resolve(it) }.filter { it.isFile }
            .forEach { bundled += it }
        if (bundled.isEmpty()) { logger.lifecycle("dist/receiver not found: building without the receiver download"); return }
        // Asset merging un-gzips "*.gz", so the tarball is stored as ".tgz" (AssetReceiverDownloads maps the name back).
        bundled.filter { it.parentFile != out }.forEach { it.copyTo(out.resolve(it.name.replace(".tar.gz", ".tgz")), overwrite = true) }
        // SHA256SUMS of exactly what is served (plus the .exe inside the Windows zip), `sha256sum -c` format.
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        val lines = bundled.map { "${sha(it)}  ${it.name}" } + (if (exe.isFile) listOf("${sha(exe)}  GallerySlim-Receiver.exe") else emptyList())
        out.resolve("SHA256SUMS").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// -PreceiverDist=<dir> points elsewhere (e.g. a CI artifact folder, or a missing one to check the fallback).
val receiverDist = rootProject.layout.projectDirectory.dir(providers.gradleProperty("receiverDist").getOrElse("dist/receiver"))
val bundleReceiverDownloads = tasks.register<BundleReceiverDownloads>("bundleReceiverDownloads") {
    distDir.set(receiverDist)
    sources.from(
        receiverDist.file("windows-amd64/GallerySlim-Receiver.exe"),
        receiverDist.file("GallerySlim-Receiver-macos-arm64.zip"),
        receiverDist.file("GallerySlim-Receiver-macos-amd64.zip"),
        receiverDist.file("GallerySlim-Receiver-linux-amd64.tar.gz"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/receiverAssets"))
}
// Registered as generated assets: AGP runs the task before merge<Variant>Assets for every variant.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleReceiverDownloads, BundleReceiverDownloads::outputDir)
    }
}

// Room schema history (GsDatabase exportSchema = true): every version's JSON lands in app/schemas/.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)

    implementation(libs.media3.transformer)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.heifwriter)
    implementation(libs.exifinterface)
    implementation(libs.documentfile)
    implementation(libs.work.runtime)
    // On-device, bundled (no Play services needed); approved by the developer 2026-10-20.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.face.detection)
    // Send-to-laptop: QR scan (camera) + QR drawing; approved by the developer 2026-10-20.
    implementation(libs.mlkit.barcode.scanning)
    // On-device photo search labels (bundled model, no network); approved by the developer 2026-10-21.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
