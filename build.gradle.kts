plugins {
    kotlin("jvm")
    kotlin("kapt")
    kotlin("plugin.serialization")
    `maven-publish`
}

group = "it.pureorigins"
version = "1.0.0"

repositories {
    mavenCentral()
    maven(url = "https://nexus.velocitypowered.com/repository/maven-public/")
    maven(url = "https://jitpack.io")
}

val fat: Configuration by configurations.creating {
    isTransitive = true
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.0.0")
    compileOnly("com.github.PureOrigins:velocity-language-kotlin:1.0.0")
    compileOnly("com.github.PureOrigins:VelocityConfiguration:1.0.1")
    kapt("com.velocitypowered:velocity-api:3.0.0")
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
