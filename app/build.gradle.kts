// GautamKitor/app/build.gradle.kts (App Module)
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    kotlin("plugin.serialization") version "1.9.23"
}

group = "com.example"
version = "1.0.0"

application {
    mainClass.set("com.example.ApplicationKt")
}

dependencies {
    // Ktor Core & Netty Engine
    implementation(libs.ktor.server.core.jvm)
    implementation(libs.ktor.server.netty.jvm)

    // Content Negotiation & JSON Serialization
    implementation(libs.ktor.server.content.negotiation.jvm)
    implementation(libs.ktor.serialization.kotlinx.json.jvm)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.ktor.server.tests.jvm)
    testImplementation(libs.kotlin.test.junit)
}

// Custom stage task for cloud hosting
tasks.register("stage") {
    dependsOn("installDist")
}