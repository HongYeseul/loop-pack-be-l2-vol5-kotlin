plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    api(project(":modules:jpa"))
    api(project(":modules:redis"))

    testImplementation("com.tngtech.archunit:archunit:${project.properties["archUnitVersion"]}")
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
