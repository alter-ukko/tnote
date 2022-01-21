plugins {
    kotlin("jvm") version "1.6.10"
    application
}

group = "com.dimdarkevil"
version = "1.0-SNAPSHOT"
description = "Command-line note taking app"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10")
    implementation("org.xerial:sqlite-jdbc:3.36.0.2")
    implementation("org.slf4j:slf4j-simple:1.7.32")
    implementation("com.offbytwo:docopt:0.6.0.20150202")
    implementation("com.atlassian.commonmark:commonmark:0.17.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.17.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.dimdarkevil.tnote.NoteTaker")
}
