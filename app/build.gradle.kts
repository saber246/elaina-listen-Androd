import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.elainalisten"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elainalisten"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "litertlm"
    }
}

abstract class PrepareKokoroModel : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localModel: RegularFileProperty

    @get:OutputDirectory
    abstract val assetDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = assetDirectory.file("kokoro_int8_fp16.litertlm").get().asFile
        output.parentFile.mkdirs()
        val temporary = File(temporaryDir, output.name)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = localModel.orNull?.asFile?.inputStream()
                ?: URI.create(modelUrl.get()).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }.getInputStream()
            DigestInputStream(source.buffered(), digest).use { input ->
                temporary.outputStream().buffered().use { input.copyTo(it) }
            }
            check(HexFormat.of().formatHex(digest.digest()) == sha256.get()) {
                "Kokoro model SHA-256 mismatch"
            }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: java.io.IOException) {
            throw GradleException(
                "Cannot prepare Kokoro. Supply a verified local file with -PkokoroModelFile=<path>.",
                error,
            )
        } finally {
            temporary.delete()
        }
    }
}

val prepareKokoroModel by tasks.registering(PrepareKokoroModel::class) {
    modelUrl.set(
        "https://huggingface.co/litert-community/Kokoro-82M/resolve/" +
            "bf1c2405642aa310bd210205c22d3ae1abc253fb/kokoro_int8_fp16.litertlm",
    )
    sha256.set("e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1")
    localModel.set(layout.file(providers.gradleProperty("kokoroModelFile").map { rootProject.file(it) }))
    assetDirectory.set(layout.buildDirectory.dir("generated/kokoro/assets"))
}

ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareKokoroModel, PrepareKokoroModel::assetDirectory)
}

dependencies {
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
    implementation(libs.mediapipe.tasks.audio) {
        exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
    }
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    // Older Espresso versions use an input API removed in Android 17.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
