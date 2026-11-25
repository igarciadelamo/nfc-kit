import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "es.igarciadelamo.nfctest"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Same rationale as :nfc-reader: keep consumers free to compile below 37.
androidComponents {
    onVariants { variant ->
        variant.aarMetadata.minCompileSdk {
            version = release(30)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()

                pom {
                    name.set("nfc-test")
                    description.set("Debug-only NFC tag injection receiver and tag factory.")
                    url.set("https://github.com/igarciadelamo/nfc-kit")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("igarciadelamo")
                            name.set("Isidro Garcia del Amo")
                            email.set("isidro.garcia@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git@github.com:igarciadelamo/nfc-kit.git")
                        developerConnection.set("scm:git:git@github.com:igarciadelamo/nfc-kit.git")
                        url.set("https://github.com/igarciadelamo/nfc-kit")
                    }
                }
            }
        }
    }
}


dependencies {
    api(project(":nfc-reader"))

    testImplementation(libs.junit)
    testImplementation(libs.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
