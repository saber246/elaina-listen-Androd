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
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "com.elainalisten.ElainaTestRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

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
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    // Older Espresso versions use an input API removed in Android 17.
    androidTestImplementation(libs.androidx.test.espresso.core)
}
