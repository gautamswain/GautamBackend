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
    implementation("io.ktor:ktor-server-call-logging:2.3.8")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Google Sheets API & Authentication
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20220927-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")

    // Google GenAI SDK (Gemini AI Integration)
    implementation("com.google.genai:google-genai-kotlin:1.0.0")

    // Testing
    testImplementation(libs.ktor.server.tests.jvm)
    testImplementation(libs.kotlin.test.junit)
}

// Custom stage task for cloud hosting (Render Deployment)
tasks.register("stage") {
    dependsOn("installDist")
}