plugins {
    `java-library`
}

group = "com.leadsystems.minecraft"
version = "1.6.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // The Paper 26.3 build MockBukkit 4.118.0 is built against; keep both in step when upgrading.
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")

    // Floodgate & Cumulus for Bedrock forms (same coordinates as the root LeadSystems build).
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")

    // JetBrains Annotations
    compileOnly("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    // Unit Testing
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable")
    testImplementation("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    testImplementation("org.geysermc.cumulus:cumulus:1.1.2")
    // Paper 26.3 ships sqlite-jdbc 3.49.1.0 at runtime (paper-server runtimeOnly); tests use the same version.
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
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
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:removal"))
    }

    compileTestJava {
        options.encoding = "UTF-8"
    }

    processResources {
        inputs.property("pluginVersion", project.version.toString())
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version, "apiVersion" to "26.3")
        }
    }

    jar {
        archiveBaseName.set("Ascension")
        archiveClassifier.set("paper26.3")
        from("LICENSE.md")
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
