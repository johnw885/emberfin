plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.johnw.emberfin.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(libs.jellyfin.core)
    implementation(libs.androidx.core.ktx)
    // The SDK logs through SLF4J but doesn't ship the API at runtime.
    implementation(libs.slf4j.api)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
