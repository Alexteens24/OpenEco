plugins {
    java
}

group = "dev.alexisbinh"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.jar {
    // Replace @version@ placeholder in the compiled plugin descriptor
    filter { line -> line.replace("@version@", version.toString()) }
}

tasks.test {
    useJUnitPlatform()
}
