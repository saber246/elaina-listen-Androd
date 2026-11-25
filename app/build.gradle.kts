import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val matchaAbi = providers.gradleProperty("elainaAbi").orElse("arm64-v8a").get()
require(matchaAbi in setOf("arm64-v8a", "x86_64")) { "Use -PelainaAbi=arm64-v8a or x86_64" }

val releaseSigningFile = file(
    providers.environmentVariable("ELAINA_RELEASE_SIGNING_FILE")
        .orElse(providers.systemProperty("user.home").map { "$it/.elaina-listen/release-signing.properties" }).get(),
)
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use(::load)
}

android {
    namespace = "com.elainalisten"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elainalisten"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "0.13.3"
        ndk.abiFilters += matchaAbi
        testInstrumentationRunner = "com.elainalisten.ElainaTestRunner"
    }

    if (releaseSigning.isNotEmpty()) {
        signingConfigs.create("elainaRelease") {
            storeFile = releaseSigningFile.parentFile.resolve(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("elainaRelease")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.jniLibs.useLegacyPackaging = true
}

ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

abstract class PrepareMatcha : DefaultTask() {
    @get:Input
    abstract val pinnedFiles: MapProperty<String, String>

    @get:Internal
    abstract val downloadCache: DirectoryProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @get:OutputFile
    abstract val runtime: RegularFileProperty

    @TaskAction
    fun prepare() {
        val cache = downloadCache.get().asFile.apply { mkdirs() }
        pinnedFiles.get().forEach { (name, descriptor) ->
            val (url, sha256) = descriptor.split('|', limit = 2)
            val cached = cache.resolve(name)
            if (!cached.isFile || hash(cached) != sha256) {
                val temporary = File.createTempFile("matcha-", ".part", cache)
                try {
                    try {
                        URI.create(url).toURL().openConnection().apply {
                            connectTimeout = 20_000
                            readTimeout = 120_000
                        }.getInputStream().use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (error: IOException) {
                        throw GradleException("Cannot download $name. Put the verified file in $cache.", error)
                    }
                    check(hash(temporary) == sha256) { "SHA-256 mismatch for $name" }
                    Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temporary.delete()
                }
            }
            val target = if (name.endsWith(".aar")) runtime.get().asFile
                else assets.file("matcha/$name").get().asFile
            target.parentFile.mkdirs()
            Files.copy(cached.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

val prepareMatcha by tasks.registering(PrepareMatcha::class) {
    val model = "https://huggingface.co/csukuangfj/matcha-icefall-zh-baker/resolve/" +
        "6ccce73a41f1f4be966c485c75bcbf9d13df4819"
    val vocoder = "https://huggingface.co/k2-fsa/sherpa-onnx-models/resolve/05ebbab/vocoder-models"
    val release = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.21"
    pinnedFiles.set(mapOf(
        "model-steps-3.onnx" to "$model/model-steps-3.onnx|47e82b11454edeeb65adc4e44a24fd54c6715cd4d875cc2a20f75545ecc32d4d",
        "vocos-22khz-univ.onnx" to "$vocoder/vocos-22khz-univ.onnx|0574a135aa1db2de6e181050db2ec528496cacd4a4701fc5d7faf9f9804c0081",
        "tokens.txt" to "$model/tokens.txt|56209b2bf609d5ac1d66ede6dae7bf5254bd3f8aa24c4a6823713d5b884d87ba",
        "lexicon.txt" to "$model/lexicon.txt|38b886d46aefa50da6322a64d72fd595d5f4fae1051adb160d647541b1e0a4a2",
        "phone.fst" to "$model/phone.fst|1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7",
        "date.fst" to "$model/date.fst|eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718",
        "number.fst" to "$model/number.fst|743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd",
        "sherpa-onnx-1.12.21.aar" to "$release/sherpa-onnx-1.12.21.aar|aaf81ccfe4d019e493e0bcf4b579f01115af092aa61800b79a6d09a9fcd10dff",
    ))
    downloadCache.set(rootProject.layout.projectDirectory.dir(".gradle/matcha"))
    assets.set(layout.buildDirectory.dir("generated/matcha/assets"))
    runtime.set(layout.buildDirectory.file("generated/matcha/runtime/sherpa-onnx-1.12.21.aar"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareMatcha, PrepareMatcha::assets)
}

dependencies {
    implementation(files(prepareMatcha.flatMap { it.runtime }).builtBy(prepareMatcha))
    coreLibraryDesugaring(libs.android.desugar.nio)
    implementation(libs.jsoup)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    // Older Espresso versions use an input API removed in Android 17.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
