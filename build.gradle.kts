import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ManagedVirtualDevice
import com.android.build.api.dsl.TestExtension
import com.android.build.api.dsl.TestOptions

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
}

/**
 * Single source of truth for Gradle Managed Devices.
 *
 * Applied by convention to every Android module, so the device set and the
 * allDevicesGroupAndroidTest alias never get copied between build files.
 */
subprojects {
    fun configureManagedDevices(testOptions: TestOptions) {
        testOptions.animationsDisabled = true

        fun device(name: String, sdk: Int): ManagedVirtualDevice =
            testOptions.managedDevices.localDevices.create(name) {
                device = "Pixel 6"
                apiLevel = sdk
                systemImageSource = "aosp"
            }

        val api30 = device("api30", 30)
        val api34 = device("api34", 34)
        val api36 = device("api36", 36)

        testOptions.managedDevices.groups.create("allDevices").targetDevices
            .addAll(listOf(api30, api34, api36))

        // AGP generates allDevicesGroupDebugAndroidTest; keep the documented
        // entry point stable in every module.
        tasks.register("allDevicesGroupAndroidTest") {
            group = "verification"
            description = "Alias for allDevicesGroupDebugAndroidTest."
            dependsOn("allDevicesGroupDebugAndroidTest")
        }
    }

    pluginManager.withPlugin("com.android.application") {
        configureManagedDevices(extensions.getByType(ApplicationExtension::class.java).testOptions)
    }
    pluginManager.withPlugin("com.android.library") {
        configureManagedDevices(extensions.getByType(LibraryExtension::class.java).testOptions)
    }
    pluginManager.withPlugin("com.android.test") {
        configureManagedDevices(extensions.getByType(TestExtension::class.java).testOptions)
    }
}
