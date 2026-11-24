plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.johnw.emberfin.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.tv.material)
    api(libs.coil.compose)
    // Pins lifecycle to the version :app uses (coil alone would pull an older, uncached one).
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
