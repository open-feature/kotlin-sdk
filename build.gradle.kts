// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library").version("7.4.2").apply(false)
    id("org.jetbrains.kotlin.android").version("1.9.10").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("11.6.1").apply(true)
    id("io.github.gradle-nexus.publish-plugin").version("1.3.0").apply(true)
}
allprojects {
    extra["groupId"] = "dev.openfeature"
// x-release-please-start-version
    ext["version"] = "0.0.3"
// x-release-please-end
}
group = project.extra["groupId"].toString()
version = project.extra["version"].toString()

nexusPublishing {
    this.repositories {
        sonatype {
            username = System.getenv("OSSRH_USERNAME")
            password = System.getenv("OSSRH_PASSWORD")
        }
    }
}
