/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// Velocity's annotation processor writes velocity-plugin.json into the main source set's
// CLASS_OUTPUT rather than a resources directory. If it ever stops doing so the jar builds
// green and then Velocity refuses to load it, so fail the build instead of shipping that.
val generatedDescriptor = layout.buildDirectory.file("classes/java/main/velocity-plugin.json")

val assertVelocityDescriptor = tasks.register("assertVelocityDescriptor") {
    description = "Fails when the Velocity plugin descriptor was not generated."
    group = "verification"
    dependsOn(tasks.named("classes"))
    doLast {
        if (!generatedDescriptor.get().asFile.isFile) {
            throw GradleException(
                "velocity-plugin.json was not generated into ${generatedDescriptor.get().asFile}. " +
                    "The jar would be unloadable by Velocity. Check that " +
                    "annotationProcessor(\"com.velocitypowered:velocity-api\") is still configured."
            )
        }
    }
}

tasks.jar {
    dependsOn(assertVelocityDescriptor)
    // Replace @version@ placeholder in the compiled plugin descriptor
    filesMatching("velocity-plugin.json") {
        filter { line -> line.replace("@version@", version.toString()) }
    }
}

tasks.named("build") {
    dependsOn(assertVelocityDescriptor)
}

tasks.test {
    useJUnitPlatform()
}
