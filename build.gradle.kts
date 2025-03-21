// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library").version("7.4.2").apply(false)
    id("com.android.application").version("7.4.2").apply(false)
    id("org.jetbrains.kotlin.android").version("1.8.10").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("11.6.1").apply(true)
    id("io.github.gradle-nexus.publish-plugin").version("2.0.0").apply(true)
    id("org.jetbrains.kotlinx.binary-compatibility-validator").version("0.17.0").apply(false)
}
allprojects {
    extra["groupId"] = "dev.openfeature"
// x-release-please-start-version
    ext["version"] = "0.4.1"
// x-release-please-end
}
group = project.extra["groupId"].toString()
version = project.extra["version"].toString()

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(
                uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            )
            username = System.getenv("OSSRH_USERNAME")
            password = System.getenv("OSSRH_PASSWORD")
        }
    }
}