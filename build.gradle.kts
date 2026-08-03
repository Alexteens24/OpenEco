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

import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.attributes.java.TargetJvmVersion
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// VaultUnlocked 2.20.0 module metadata declares jvmCompatibility=25 — patch it back to 21
abstract class VaultJvmFix : ComponentMetadataRule {
    override fun execute(ctx: ComponentMetadataContext) {
        ctx.details.allVariants {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
            }
        }
    }
}

plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
}

group = "dev.alexisbinh"
version = "1.6.1"

allprojects {
    repositories {
        maven("https://repo.faststats.dev/releases")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/creatorfromhell/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    api(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("org.xerial:sqlite-jdbc:3.53.2.0")
    compileOnly("com.h2database:h2:2.4.240")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    implementation("dev.faststats.metrics:bukkit:0.27.2")
    compileOnly("com.zaxxer:HikariCP:7.1.0")
    compileOnly("com.mysql:mysql-connector-j:9.7.0") {
        exclude(group = "com.google.protobuf") // only needed for X Protocol (mysqlx://), not standard JDBC
    }
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    compileOnly("org.postgresql:postgresql:42.7.13")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("net.milkbowl.vault:VaultUnlockedAPI:2.20")
    testImplementation("me.clip:placeholderapi:2.12.3")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.7.0")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    testRuntimeOnly("org.postgresql:postgresql:42.7.13")
    testRuntimeOnly("com.h2database:h2:2.4.240")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.2.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    components {
        withModule<VaultJvmFix>("net.milkbowl.vault:VaultUnlockedAPI")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("org.bstats", "dev.alexisbinh.openeco.libs.bstats")
    relocate("dev.faststats", "dev.alexisbinh.openeco.libs.faststats")
    from("LICENSE") {
        into("")
    }
    from("NOTICE") {
        into("")
    }
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

if (providers.environmentVariable("JITPACK").orNull == "true") {
    tasks.named<Jar>("jar") { enabled = false }
    tasks.named<ShadowJar>("shadowJar") { enabled = false }
    tasks.named("assemble") { enabled = false }
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
