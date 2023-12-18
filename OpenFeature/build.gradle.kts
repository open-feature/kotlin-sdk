// ktlint-disable max-line-length
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
    id("org.jlleitschuh.gradle.ktlint")
    kotlin("plugin.serialization") version "1.9.10"
}

val releaseVersion = project.extra["version"].toString()

android {
    namespace = "dev.openfeature.sdk"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        version = releaseVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.extra["groupId"].toString()
            artifactId = "kotlin-sdk"
            version = releaseVersion

            pom {
                name.set("OpenFeature Android SDK")
                description.set(
                    "This is the Android implementation of OpenFeature, a vendor-agnostic abstraction library for evaluating feature flags."
                )
                url.set("https://openfeature.dev")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("vahidlazio")
                        name.set("Vahid Torkaman")
                        email.set("vahidt@spotify.com")
                    }
                    developer {
                        id.set("fabriziodemaria")
                        name.set("Fabrizio Demaria")
                        email.set("fdema@spotify.com")
                    }
                    developer {
                        id.set("nicklasl")
                        name.set("Nicklas Lundin")
                        email.set("nicklasl@spotify.com")
                    }
                    developer {
                        id.set("nickybondarenko")
                        name.set("Nicky Bondarenko")
                        email.set("nickyb@spotify.com")
                    }
                }
                scm {
                    connection.set(
                        "scm:git:https://github.com/open-feature/kotlin-sdk.git"
                    )
                    developerConnection.set(
                        "scm:git:ssh://open-feature/kotlin-sdk.git"
                    )
                    url.set("https://github.com/open-feature/kotlin-sdk")
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

signing {
    sign(publishing.publications["release"])
}