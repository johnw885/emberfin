plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.johnw.reelfin.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.jellyfin.core)
    implementation(libs.androidx.core.ktx)
    // The SDK logs through SLF4J but doesn't ship the API at runtime.
    implementation(libs.slf4j.api)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
