plugins {
    kotlin("jvm") version "2.2.20"
    application
}

val ktorVersion = "3.5.1"

group = "dev.arianna"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("dev.arianna.cli.MainKt")
    applicationName = "learn"
}

val autonomousRuntimeDir = layout.buildDirectory.dir("autonomous-runtime")
val autonomousPackageDir = layout.buildDirectory.dir("autonomous-package")

tasks.register("packageAutonomous") {
    group = "distribution"
    description = "Builds a self-contained Arianna app image with an embedded Java runtime."
    dependsOn(tasks.named("installDist"))

    doLast {
        val javaHome = File(System.getProperty("java.home"))
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val jlink = javaHome.resolve("bin/jlink$executableSuffix")
        val jpackage = javaHome.resolve("bin/jpackage$executableSuffix")
        require(System.getProperty("java.specification.version") == "21") {
            "packageAutonomous must run with JDK 21; found ${System.getProperty("java.version")}"
        }
        require(jlink.isFile) { "jlink was not found in $javaHome; build with a JDK 21 installation" }
        require(jpackage.isFile) { "jpackage was not found in $javaHome; build with a JDK 21 installation" }

        val installLib = layout.buildDirectory.dir("install/learn/lib").get().asFile
        val runtimeDir = autonomousRuntimeDir.get().asFile
        val packageDir = autonomousPackageDir.get().asFile
        project.delete(runtimeDir, packageDir)
        packageDir.mkdirs()

        exec {
            commandLine(
                jlink.absolutePath,
                "--add-modules", "java.se,jdk.crypto.ec,jdk.unsupported",
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress=2",
                "--output", runtimeDir.absolutePath
            )
        }

        val mainJar = installLib.resolve("arianna-${project.version}.jar")
        require(mainJar.isFile) { "Application jar was not found: $mainJar" }

        exec {
            commandLine(
                jpackage.absolutePath,
                "--type", "app-image",
                "--name", "learn",
                "--app-version", "1.0.0",
                "--input", installLib.absolutePath,
                "--main-jar", mainJar.name,
                "--main-class", application.mainClass.get(),
                "--runtime-image", runtimeDir.absolutePath,
                "--dest", packageDir.absolutePath,
                "--java-options", "-Dfile.encoding=UTF-8"
            )
        }

        logger.lifecycle("Autonomous Arianna package: ${packageDir.absolutePath}")
    }
}

tasks.register<org.gradle.api.tasks.bundling.Zip>("packageAutonomousZip") {
    group = "distribution"
    description = "Zip the self-contained Arianna app image for distribution."
    dependsOn("packageAutonomous")

    val os = System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val arch = System.getProperty("os.arch").lowercase().replace(Regex("[^a-z0-9]+"), "-")
    archiveFileName.set("learn-${project.version.toString().removeSuffix("-SNAPSHOT")}-$os-$arch.zip")
    destinationDirectory.set(autonomousPackageDir)
    from(autonomousPackageDir) {
        exclude("*.zip")
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    // Arianna uses its own small logger; keep transitive SLF4J users quiet.
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}
