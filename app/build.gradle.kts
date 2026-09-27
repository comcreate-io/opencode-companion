plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.local.opencodecompanion"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.opencodecompanion"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-dev"
        testInstrumentationRunner = "dev.local.opencodecompanion.connected.FixtureTestRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-candidate"
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = true }
}

dependencies {
    implementation(project(":client"))
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Android 16 removed InputManager.getInstance; 3.7 uses the public system service.
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.okhttp.tls)
}
