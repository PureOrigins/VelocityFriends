plugins {
    kotlin("jvm") version "1.6.10"
    kotlin("kapt") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    `maven-publish`
}

group = "it.pureorigins"
version = "1.0.1"

repositories {
    mavenCentral()
    maven(url = "https://nexus.velocitypowered.com/repository/maven-public/")
    maven(url = "https://jitpack.io")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.0.1")
    compileOnly("com.github.PureOrigins:velocity-language-kotlin:1.0.0")
    compileOnly("com.github.PureOrigins:VelocityConfiguration:1.0.1")
    kapt("com.velocitypowered:velocity-api:3.0.1")
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(8))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.PureOrigins"
            artifactId = project.name
            version = version
            
            from(components["kotlin"])
            artifact(tasks["kotlinSourcesJar"])
        }
    }
}
