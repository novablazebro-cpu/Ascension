plugins {
    `java-library`
}

group = "com.leadsystems.minecraft"
version = "1.5.0"
val paper26 = providers.gradleProperty("paper26").isPresent

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${if (paper26) "26.2.build.128-stable" else "1.21.4-R0.1-SNAPSHOT"}")

    // Floodgate & Cumulus for Bedrock forms (same coordinates as the root LeadSystems build).
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")

    // JetBrains Annotations
    compileOnly("org.jetbrains:annotations:26.0.1")
    testCompileOnly("org.jetbrains:annotations:26.0.1")

    // Unit Testing
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    testImplementation("org.geysermc.cumulus:cumulus:1.1.2")
    // Paper ships sqlite-jdbc 3.47.0.0 at runtime (local-test-server/libraries); tests use the same version.
    testImplementation("org.xerial:sqlite-jdbc:3.47.0.0")
    // MockBukkit 4.45.1 is the last release built against paper-api 1.21.4 (4.46+ targets 1.21.5).
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.45.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (paper26) 25 else 21))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(if (paper26) 25 else 21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    compileTestJava {
        options.encoding = "UTF-8"
    }

    processResources {
        inputs.property("pluginVersion", project.version.toString())
        inputs.property("pluginApiVersion", if (paper26) "26.2" else "1.21")
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version, "apiVersion" to if (paper26) "26.2" else "1.21")
        }
    }

    jar {
        archiveBaseName.set("Ascension")
        from("LICENSE.md")
        if (paper26) archiveClassifier.set("paper26")
        // API-only plugin (no NMS): tell Paper it is already Mojang-mapped so it skips remapping on startup.
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
    }

    test {
        useJUnitPlatform()
        // Mockito attaches its inline agent at runtime; declare it so the JDK does not warn.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
