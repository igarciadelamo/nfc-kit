plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "es.igarciadelamo.nfctest.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "es.igarciadelamo.nfctest.sample"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}


dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * Release guard: the debug-only module must never leak into the release APK.
 * Runs as part of `check`, after `assembleRelease`.
 */
val verifyReleaseHasNoNfcTest = tasks.register("verifyReleaseHasNoNfcTest") {
    group = "verification"
    description = "Fails if the debug-only nfc-test module leaks into the release APK."
    dependsOn("assembleRelease")

    doLast {
        val forbiddenClasses = listOf(
            "Les/igarciadelamo/nfctest/TagFactory;",
            "Les/igarciadelamo/nfctest/NfcTestReceiver;",
            "Les/igarciadelamo/nfctest/NfcTestInitProvider;",
            "Les/igarciadelamo/nfctest/InjectableNfcReader;",
            "Les/igarciadelamo/nfctest/NfcTestLaunchActivity;",
            "Les/igarciadelamo/nfctest/NfcIntentResolver;",
            "Les/igarciadelamo/nfctest/sample/e2e/",
        )
        val forbiddenManifest = listOf(
            "NfcTestReceiver",
            "NfcTestInitProvider",
            "NfcTestLaunchActivity",
            "nfctest-init",
            "android.permission.DUMP",
        )
        val failures = mutableListOf<String>()

        val mergedManifests = fileTree(layout.buildDirectory.dir("intermediates")) {
            include("**/release/**/AndroidManifest.xml")
        }.files.filter { it.isFile }

        if (mergedManifests.isEmpty()) {
            failures += "no merged release manifest found under build/intermediates"
        }
        mergedManifests.forEach { manifest ->
            val text = manifest.readText()
            forbiddenManifest.filter { text.contains(it) }.forEach {
                failures += "${manifest.relativeTo(projectDir)} contains \"$it\""
            }
        }

        val apk = fileTree(layout.buildDirectory.dir("outputs/apk/release")) {
            include("**/*.apk")
        }.files.firstOrNull()
            ?: error("release APK not found; did assembleRelease run?")

        val extracted = File(temporaryDir, "apk").apply { deleteRecursively(); mkdirs() }
        copy {
            from(zipTree(apk)) { include("classes*.dex") }
            into(extracted)
        }
        val dexBytes = extracted.listFiles().orEmpty().fold(ByteArray(0)) { acc, file ->
            acc + file.readBytes()
        }
        val dexText = String(dexBytes, Charsets.ISO_8859_1)
        forbiddenClasses.filter { dexText.contains(it) }.forEach {
            failures += "${apk.name} dex contains \"$it\""
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Release build contains debug-only nfc-test artifacts:\n" +
                    failures.joinToString("\n") { " - $it" },
            )
        }
        logger.lifecycle(
            "verifyReleaseHasNoNfcTest: release APK (${apk.name}) and " +
                "${mergedManifests.size} merged manifest(s) are clean",
        )
    }
}

tasks.named("check") {
    dependsOn(verifyReleaseHasNoNfcTest)
}
