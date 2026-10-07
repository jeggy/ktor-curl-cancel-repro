plugins {
    kotlin("multiplatform") version "2.3.21"
}

kotlin {
    linuxX64 {
        binaries.executable { entryPoint = "main"; baseName = "repro" }
    }
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.6.0")
                implementation("io.ktor:ktor-server-cio:3.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
