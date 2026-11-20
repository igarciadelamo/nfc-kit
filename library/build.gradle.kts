plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.nfctest"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true

        managedDevices {
            val api30 = localDevices.create("api30") {
                device = "Pixel 6"
                apiLevel = 30
                systemImageSource = "aosp"
            }
            val api34 = localDevices.create("api34") {
                device = "Pixel 6"
                apiLevel = 34
                systemImageSource = "aosp"
            }
            val api36 = localDevices.create("api36") {
                device = "Pixel 6"
                apiLevel = 36
                systemImageSource = "aosp"
            }

            groups {
                create("allDevices") {
                    targetDevices.add(api30)
                    targetDevices.add(api34)
                    targetDevices.add(api36)
                }
            }
        }
    }
}

dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// The spike's documented entry point. GMD names the real task
// allDevicesGroupDebugAndroidTest; this alias keeps the README command stable.
tasks.register("allDevicesGroupAndroidTest") {
    group = "verification"
    description = "Alias for allDevicesGroupDebugAndroidTest."
    dependsOn("allDevicesGroupDebugAndroidTest")
}
