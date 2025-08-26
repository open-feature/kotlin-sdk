// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library").version("8.12.2").apply(false)
    id("com.android.application").version("8.12.2").apply(false)
    id("org.jetbrains.kotlin.multiplatform").version("2.2.10").apply(false)
    id("org.jetbrains.kotlin.plugin.compose").version("2.2.10").apply(false)
    id("org.jetbrains.dokka").version("2.0.0").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("11.6.1").apply(true)
    id("io.github.gradle-nexus.publish-plugin").version("2.0.0").apply(true)
    id("org.jetbrains.kotlinx.binary-compatibility-validator").version("0.18.1").apply(false)
    id("com.vanniktech.maven.publish").version("0.34.0").apply(false)
}
allprojects {
    extra["groupId"] = "dev.openfeature"
// x-release-please-start-version
    ext["version"] = "0.6.2"
// x-release-please-end
}

// Allow JitPack (or callers) to override group and version with -Pgroup/-Pversion
val overriddenGroup: String? = if (project.hasProperty("group")) project.property("group").toString() else null
val overriddenVersion: String? = if (project.hasProperty("version")) project.property("version").toString() else null

group = overriddenGroup ?: project.extra["groupId"].toString()
version = overriddenVersion ?: project.extra["version"].toString()

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username = System.getenv("CENTRAL_USERNAME")
            password = System.getenv("CENTRAL_PASSWORD")
        }
    }
}