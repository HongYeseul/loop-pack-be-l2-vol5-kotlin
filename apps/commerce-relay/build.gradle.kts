import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

// commerce-api 가 relay jar 를 참조하므로 evaluationDependsOn 대신 실행 시점에 jar 위치를 찾음
val e2eJars = listOf(":apps:commerce-api", ":apps:commerce-streamer")

tasks.test {
    dependsOn(e2eJars.map { "$it:bootJar" })
    inputs.files(provider { e2eJars.map { project(it).tasks.named<BootJar>("bootJar").get().archiveFile } })
    doFirst {
        val jar = { path: String -> project(path).tasks.named<BootJar>("bootJar").get().archiveFile.get().asFile.absolutePath }
        systemProperty("week7.api-jar", jar(":apps:commerce-api"))
        systemProperty("week7.streamer-jar", jar(":apps:commerce-streamer"))
        systemProperty("week7.evidence-directory", layout.buildDirectory.dir("reports/week7").get().asFile.absolutePath)
    }
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test-fixtures
    testImplementation("com.tngtech.archunit:archunit:${project.properties["archUnitVersion"]}")
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:kafka")))
}
