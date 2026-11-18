plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    api(project(":modules:jpa"))
    api("org.springframework:spring-web")

    kapt("com.querydsl:querydsl-apt::jakarta")
}
