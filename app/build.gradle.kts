plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.kotlin.serialization)
}

group = "com.example"
version = "1.0.0"

application {
    mainClass.set("com.example.ApplicationKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Ktor Core & Netty Engine
    implementation("io.ktor:ktor-server-core-jvm:2.3.10")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.10")

    // Content Negotiation & JSON Serialization
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.10")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.10")

    // Logging Plugins
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.10")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Google Sheets API & OAuth Credentials
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20220927-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")

    // Official Google GenAI SDK
    implementation("com.google.genai:google-genai:1.0.0")

    // 🚀 Public Turso/LibSQL JDBC Driver from Maven Central (No 401 Auth Error)
    implementation("com.dbeaver.jdbc:com.dbeaver.jdbc.driver.libsql:1.0.4")

    // HikariCP & JetBrains Exposed ORM
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.50.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.0")

    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.23")
}

tasks.register("stage") {
    description = "Stages the application distribution for Render deployment."
    dependsOn("installDist")
}