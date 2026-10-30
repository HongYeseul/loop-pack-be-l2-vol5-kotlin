import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

evaluationDependsOn(":apps:commerce-streamer")
val streamerJar = project(":apps:commerce-streamer").tasks.named<BootJar>("bootJar")

tasks.test {
    dependsOn(streamerJar)
    inputs.file(streamerJar.flatMap { it.archiveFile })
    doFirst {
        systemProperty("week6.streamer-jar", streamerJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("week6.evidence-directory", layout.buildDirectory.dir("reports/week6").get().asFile.absolutePath)
        systemProperty("week6.source-revision", project.version.toString())
    }
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit:${project.properties["archUnitVersion"]}")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}
