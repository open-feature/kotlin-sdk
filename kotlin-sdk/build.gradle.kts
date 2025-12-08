import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("com.vanniktech.maven.publish")
}

// Resolve group/version allowing overrides from -Pgroup/-Pversion (used by JitPack)
val overriddenGroup: String? = if (project.hasProperty("group")) project.property("group").toString() else null
val overriddenVersion: String? = if (project.hasProperty("version")) project.property("version").toString() else null
val resolvedGroup = overriddenGroup ?: project.extra["groupId"].toString()
val resolvedVersion = overriddenVersion ?: project.extra["version"].toString()

group = resolvedGroup
version = resolvedVersion

val isJitpack = System.getenv("JITPACK") == "true" || project.hasProperty("jitpack")

kotlin {
    androidTarget {
        publishLibraryVariants("release")

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }
    linuxX64 {}

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    
    js {
        nodejs {}
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlin:kotlin-test:2.2.10")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.7.3")
        }
    }
}

android {
    namespace = "dev.openfeature.kotlin.sdk"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        version = resolvedVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Configure Dokka for documentation
dokka {
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }
    dokkaSourceSets.commonMain {
        sourceLink {
            localDirectory.set(file("src/"))
            remoteUrl("https://github.com/open-feature/kotlin-sdk/tree/main/kotlin-sdk/src")
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
            androidVariantsToPublish = listOf("release")
        )
    )
    if (!isJitpack) {
        signAllPublications()
    }

    pom {
        name.set("OpenFeature Kotlin SDK")
        description.set(
            "This is the Kotlin implementation of OpenFeature, a vendor-agnostic abstraction library for evaluating feature flags."
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
}

// Configure publishing to GitHub Packages
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "open-feature/kotlin-sdk"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: System.getenv("GITHUB_USER")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
