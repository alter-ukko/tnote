plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.xerial:sqlite-jdbc:3.36.0.2")
    implementation("org.slf4j:slf4j-simple:1.7.32")
    implementation("com.offbytwo:docopt:0.6.0.20150202")
    implementation("com.atlassian.commonmark:commonmark:0.17.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.17.0")
    testImplementation(kotlin("test"))
}

tasks.withType<Jar> {
    archiveFileName.set("${project.name}.jar")
}

tasks.withType<ProcessResources> {
    eachFile {
        if (name == "version.properties") {
            filter { line ->
                line.replace("%project.version%", "${project.version}")
            }
        }
    }
}

application {
    mainClass.set("com.dimdarkevil.tnote.NoteTaker")
    applicationName = "tn"
}

distributions {
    main {
        distributionBaseName.set(project.name)
        contents {
            from("README.md")
        }
    }
}

task<CreateStartScripts>("todoStartScript") {
    mainClass.set("com.dimdarkevil.tnote.TodoTaker")
    applicationName = "td"
    outputDir = file("build/scripts")
    classpath = project.tasks.getAt(JavaPlugin.JAR_TASK_NAME).outputs.files.plus(project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
}

task<CreateStartScripts>("noteQueryStartScript") {
    mainClass.set("com.dimdarkevil.tnote.NoteQuery")
    applicationName = "tnq"
    outputDir = file("build/scripts")
    classpath = project.tasks.getAt(JavaPlugin.JAR_TASK_NAME).outputs.files.plus(project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME))
}

tasks.distTar {
    compression = Compression.GZIP
    dependsOn("todoStartScript")
    dependsOn("noteQueryStartScript")
}

tasks.distZip {
    dependsOn("todoStartScript")
    dependsOn("noteQueryStartScript")
}
