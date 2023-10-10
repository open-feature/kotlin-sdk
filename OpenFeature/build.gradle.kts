// ktlint-disable max-line-length
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("org.jlleitschuh.gradle.ktlint")
    kotlin("plugin.serialization") version "1.9.10"
}

// x-release-please-start-version
val releaseVersion = "0.0.2"
// x-release-please-end

android {
    namespace = "dev.openfeature.sdk"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        version = releaseVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "dev.openfeature"
                artifactId = "kotlin-sdk"
                version = releaseVersion

                from(components["release"])
                artifact(androidSourcesJar.get())

                pom {
                    name.set("OpenfeatureSDK")
                }
            }
        }
    }
}

val androidSourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.srcDirs)
}

// Assembling should be performed before publishing package
tasks.named("publish") {
    dependsOn("assemble")
}