plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) apply false
    application
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

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}