rootProject.name = "VelocityFriends"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    
    plugins {
        val kotlinVersion = "1.5.0"
        
        kotlin("jvm") version kotlinVersion
        kotlin("kapt") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}
