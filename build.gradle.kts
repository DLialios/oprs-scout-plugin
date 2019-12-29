/*
 * This file was generated by the Gradle 'init' task.
 *
 * This is a general purpose Gradle build.
 * Learn how to create Gradle builds at https://guides.gradle.org/creating-new-gradle-builds
 */
plugins {
    kotlin("jvm") version "1.3.61"
}

version = "v0.1.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://dl.bintray.com/oprs/")
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    implementation("open-osrs:runelite-api:1.5.44")
    implementation("open-osrs:runelite-client:1.5.44")
    implementation("open-osrs:http-api:1.5.44")
    implementation("com.google.inject:guice:4.2.2")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.2.2")
    implementation("io.reactivex.rxjava2:rxjava:2.2.14")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}
