import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One dot only, read as a decimal: 0.3 < 0.31 < 0.4 < 1.0. Huginn and Muninn share the repo's VERSION file.
val versionText = rootProject.file("../VERSION").readLines().first().trim()
val versionParts = Regex("""^(\d+)\.(\d+)$""").find(versionText)?.groupValues
    ?: error("VERSION must look like 0.3 or 0.31, not \"$versionText\"")
// Same ranking as the in-app updater, so every release installs over the one before it.
val versionNumber = ((versionParts[1].toLong() * 1_000_000 + (versionParts[2] + "000000").take(6).toLong()) * 10 + 5).toInt()
// A pre-release build (-PbetaLabel="0.99 beta 12", from CI on the beta branch): the label is its versionName, and its
// versionCode stays the stable one above, so going back to stable installs over it without uninstalling.
val betaLabel = (project.findProperty("betaLabel") as String?)?.trim()?.takeIf { it.isNotEmpty() }?.also {
    if (!Regex("""^\d+\.\d+ beta \d+$""").matches(it)) error("betaLabel must look like \"0.99 beta 12\", not \"$it\"")
}

// Calls: libopus, built from source with the NDK (Android's own Opus encoder only exists from Android 10).
// Pinned version and SHA-256; fetched once into build/opus by fetchOpus, built by src/main/cpp/CMakeLists.txt.
val opusVersion = "1.5.2"
val opusSha256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
val opusDir = layout.buildDirectory.dir("opus").get().asFile
val opusSrc = File(opusDir, "opus-$opusVersion")

// Kids' TV voice processing (src/main/cpp/tvvoice): WebRTC Audio Processing (BSD-3-Clause), the Abseil headers
// its code includes (Apache-2.0) and RNNoise (BSD-3-Clause). Pinned versions and SHA-256, fetched once into
// build/tvvoice by fetchTvVoice.
data class Pinned(val name: String, val urls: List<String>, val sha256: String, val file: String)
val tvVoicePins = listOf(
    Pinned("webrtc-audio-processing 1.3",
        listOf("https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-1.3.tar.xz"),
        "2365e93e778d7b61b5d6e02d21c47d97222e9c7deff9e1d0838ad6ec2e86f1b9", "webrtc-audio-processing-1.3.tar.xz"),
    Pinned("abseil-cpp 20230125.1",
        listOf("https://github.com/abseil/abseil-cpp/archive/20230125.1.tar.gz",
            "https://github.com/mesonbuild/wrapdb/releases/download/abseil-cpp_20230125.1-4/abseil-cpp-20230125.1.tar.gz"),
        "81311c17599b3712069ded20cca09a62ab0bf2a89dfa16993786c8782b7ed145", "abseil-cpp-20230125.1.tar.gz"),
    Pinned("rnnoise 0.2",
        listOf("https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz"),
        "90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37", "rnnoise-0.2.tar.gz"),
)
val tvVoiceDir = layout.buildDirectory.dir("tvvoice").get().asFile
val webrtcSrc = File(tvVoiceDir, "webrtc-audio-processing-1.3/webrtc")
val abseilSrc = File(tvVoiceDir, "abseil-cpp-20230125.1")
val rnnoiseSrc = File(tvVoiceDir, "rnnoise-0.2")

android {
    namespace = "io.github.ravenor907.muninn"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ravenor907.muninn"
        minSdk = 26
        targetSdk = 36
        versionCode = versionNumber
        versionName = betaLabel ?: versionText
        // Native code (libopus, the Kids' TV voice library) is built for phones, tablets and TVs (ARM). The debug
        // build adds x86_64 for emulators; -PciAbi=x86_64 builds only that one, for a fast test build.
        externalNativeBuild { cmake { arguments += listOf("-DOPUS_SRC=${opusSrc.absolutePath}", "-DANDROID_STL=c++_static",
            "-DWEBRTC_APM_SRC=${webrtcSrc.absolutePath}", "-DABSEIL_SRC=${abseilSrc.absolutePath}", "-DRNNOISE_SRC=${rnnoiseSrc.absolutePath}") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Releases are signed with a private key that only CI has (secrets MUNINN_KEYSTORE_BASE64 and
    // MUNINN_KEYSTORE_PASSWORD, see .github/workflows/build.yml). Without it, builds use the repo's test key,
    // which anyone can sign with, so CI refuses to release one.
    val releaseKey = System.getenv("MUNINN_KEYSTORE").orEmpty()
    signingConfigs {
        // The repo's shared test key: debug builds, and local builds without the release key.
        create("muninn") {
            storeFile = rootProject.file("signing/test-key.p12")
            storePassword = "muninn-test"
            keyAlias = "muninn"
            keyPassword = "muninn-test"
            storeType = "pkcs12"
        }
        // A password pasted into GitHub's secret box can carry a stray space or line break.
        val releasePassword = System.getenv("MUNINN_KEYSTORE_PASSWORD").orEmpty().trim()
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = file(releaseKey)
            storePassword = releasePassword
            keyAlias = "muninn"
            keyPassword = releasePassword
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey.isNotEmpty()) "release" else "muninn")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            signingConfig = signingConfigs.getByName("muninn")
            val ciAbi = (project.findProperty("ciAbi") as String?)?.takeIf { it.isNotBlank() }
            ndk { abiFilters += ciAbi?.let { listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libraries stored compressed: a smaller download (Android unpacks them once, at install).
        jniLibs.useLegacyPackaging = true
        // Read aloud uses the speech engine's Java/Kotlin side only (libsherpa-onnx-jni.so): its C and C++ API libraries stay out.
        jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
}

val fetchOpus by tasks.registering {
    description = "Downloads the pinned libopus source and checks its SHA-256."
    outputs.dir(opusSrc)
    doLast {
        if (File(opusSrc, "CMakeLists.txt").exists()) return@doLast
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        opusDir.mkdirs()
        val tgz = File(opusDir, "opus-$opusVersion.tar.gz")
        if (!tgz.exists() || sha(tgz) != opusSha256)
            URI("https://github.com/xiph/opus/releases/download/v$opusVersion/opus-$opusVersion.tar.gz").toURL().openStream()
                .use { input -> tgz.outputStream().use { input.copyTo(it) } }
        val got = sha(tgz)
        if (got != opusSha256) { tgz.delete(); throw GradleException("libopus $opusVersion download has SHA-256 $got, expected $opusSha256") }
        project.copy { from(project.tarTree(project.resources.gzip(tgz))); into(opusDir) }
    }
}

val fetchTvVoice by tasks.registering {
    description = "Downloads the pinned Kids' TV voice processing sources and checks their SHA-256."
    outputs.dirs(webrtcSrc, abseilSrc, rnnoiseSrc)
    doLast {
        if (File(webrtcSrc, "modules/audio_processing").exists() && File(abseilSrc, "absl").exists() && File(rnnoiseSrc, "src/denoise.c").exists()) return@doLast
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        tvVoiceDir.mkdirs()
        for (p in tvVoicePins) {
            val f = File(tvVoiceDir, p.file)
            for (u in p.urls) {
                if (f.exists() && sha(f) == p.sha256) break
                try { URI(u).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } } } catch (e: Exception) { f.delete() }
            }
            val got = if (f.exists()) sha(f) else "nothing (download failed)"
            if (got != p.sha256) { f.delete(); throw GradleException("${p.name} download has SHA-256 $got, expected ${p.sha256}") }
            // tar knows .xz, which Gradle's tarTree doesn't.
            val tar = ProcessBuilder("tar", "-xf", f.absolutePath, "-C", tvVoiceDir.absolutePath).redirectErrorStream(true).start()
            val out = tar.inputStream.bufferedReader().readText()
            if (tar.waitFor() != 0) throw GradleException("Unpacking ${p.file} failed: $out")
        }
    }
}
tasks.configureEach { if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchOpus, fetchTvVoice) }

// Daily Hávamál's verses: one file for both apps, code/shared/havamal.json (Huginn builds the same file in as a Qt
// resource). Copied into the APK's assets as havamal.json, so there is no second copy in this folder to drift.
abstract class SharedHavamal : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val verses: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyIn() {
        val out = outputDir.get().asFile
        out.mkdirs()
        verses.get().asFile.copyTo(File(out, "havamal.json"), overwrite = true)
    }
}
val sharedHavamalFile = rootProject.file("../shared/havamal.json")
androidComponents {
    onVariants { variant ->
        val copy = tasks.register<SharedHavamal>("${variant.name}SharedHavamal") { verses.set(sharedHavamalFile) }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, SharedHavamal::outputDir)
    }
}

// Labs › Things to test: the beta's test list, one file for both apps (code/shared/beta-tests.json), copied into the
// APK's assets as beta-tests.json the same way as the verses above.
abstract class SharedBetaTests : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tests: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyIn() {
        val out = outputDir.get().asFile
        out.mkdirs()
        tests.get().asFile.copyTo(File(out, "beta-tests.json"), overwrite = true)
    }
}
val sharedBetaTestsFile = rootProject.file("../shared/beta-tests.json")
androidComponents {
    onVariants { variant ->
        val copy = tasks.register<SharedBetaTests>("${variant.name}SharedBetaTests") { tests.set(sharedBetaTestsFile) }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, SharedBetaTests::outputDir)
    }
}

// Read aloud's natural voices (code/shared/docs/voices.md): the hashes of the voice files (code/shared/voices.sha256),
// copied into the APK's assets as voices.sha256, so a voice is only ever used when it matches its line there.
abstract class SharedVoices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hashes: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyIn() {
        val out = outputDir.get().asFile
        out.mkdirs()
        hashes.get().asFile.copyTo(File(out, "voices.sha256"), overwrite = true)
    }
}
val sharedVoicesFile = rootProject.file("../shared/voices.sha256")
androidComponents {
    onVariants { variant ->
        val copy = tasks.register<SharedVoices>("${variant.name}SharedVoices") { hashes.set(sharedVoicesFile) }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, SharedVoices::outputDir)
    }
}

// Read aloud's speech engine, bundled: sherpa-onnx 1.13.8's Android library (Apache-2.0) with ONNX Runtime linked in
// (MIT), from its GitHub release. The build stops unless it matches its line in code/shared/voices.sha256 (and when
// that line is missing). Fetched once into build/sherpa; abiFilters keeps only the ABIs each APK needs
// (arm64-v8a and armeabi-v7a in a release). 1.13.8 is the last version that still reads Piper voices from text
// (2.0 dropped eSpeak NG).
val sherpaVersion = "1.13.8"
val sherpaAarName = "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
val sherpaAar = layout.buildDirectory.file("sherpa/$sherpaAarName").get().asFile
val fetchSherpa by tasks.registering {
    description = "Downloads the pinned sherpa-onnx Android library for Read aloud and checks its SHA-256."
    inputs.file(sharedVoicesFile)
    outputs.file(sherpaAar)
    doLast {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val line = Regex("^([0-9a-f]{64}) {2}" + Regex.escape(sherpaAarName) + "$")
        val want = sharedVoicesFile.readLines().firstNotNullOfOrNull { line.find(it.trim())?.groupValues?.get(1) }
            ?: throw GradleException("code/shared/voices.sha256 has no line for $sherpaAarName: the speech engine is native code and is never bundled unchecked (code/shared/docs/voices.md)")
        if (sherpaAar.exists() && sha(sherpaAar) == want) return@doLast
        sherpaAar.parentFile.mkdirs()
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$sherpaAarName").toURL().openStream()
            .use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
        val got = sha(sherpaAar)
        if (got != want) { sherpaAar.delete(); throw GradleException("$sherpaAarName download has SHA-256 $got, expected $want") }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchSherpa) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.zxing:core:3.5.3") // QR codes for Direct connect
    implementation("androidx.fragment:fragment-ktx:1.8.5")   // Play services pulls an old Fragment; permission requests need 1.3+
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Nostr relays (WebSockets)
    implementation("androidx.work:work-runtime-ktx:2.10.0") // checking the inbox in the background
    // Read aloud's speech engine (sherpa-onnx, fetched and checked by fetchSherpa above; Kotlin API in com.k2fsa.sherpa.onnx).
    implementation(files(sherpaAar).builtBy(fetchSherpa))
    // Ultra privacy: Tor inside Muninn's process (TorService, SOCKS5 on 127.0.0.1), no VPN. The Guardian Project's
    // tor-android (BSD-3-Clause; Tor BSD-3-Clause, OpenSSL Apache-2.0, libevent BSD-3-Clause, zlib, zstd BSD).
    // 0.4.8.22 is the newest built for compileSdk 36 and Java 21; 0.4.9.x needs compileSdk 37 and Java 24 class files.
    implementation("info.guardianproject:tor-android:0.4.8.22")
    // Video calls: the camera (encoded with Android's own VP8 encoder, see net/call/CallVideo.kt)
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax") // video messages (cap m2)
    // Picked videos (cap v3, net/VideoPick.kt): converting to 1080p30 or the 720p relay copy with the phone's own encoder.
    // About 3 MB more in the APK (no shrinking here); compare CI's APK sizes before and after.
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json is a stub in unit tests
}
