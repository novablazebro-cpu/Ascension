plugins {
    `java-library`
}

group = "com.leadsystems.minecraft"
version = "0.1.0-angler-test"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.128-stable")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    processResources {
        inputs.property("pluginVersion", project.version.toString())
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version, "apiVersion" to "26.2")
        }
    }

    jar {
        archiveBaseName.set("AnglerTest")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
    }
}
