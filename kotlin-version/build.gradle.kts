plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    // Telegram library — choose one (uncomment and adjust version if needed)
    implementation("io.github.kotlin-telegram-bot:kotlin-telegram-bot:6.0.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.google.zxing:javase:3.5.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

application {
    // fully-qualified main class
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(11)
}
