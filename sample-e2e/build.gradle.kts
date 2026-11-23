plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "dev.nfctest.sample.e2e"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":sample"

    // Run the tests in their own process: force-stop must not kill the test.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
