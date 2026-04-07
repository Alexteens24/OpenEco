import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.attributes.java.TargetJvmVersion

// VaultUnlocked 2.19.0 module metadata declares jvmCompatibility=25 — patch it back to 21
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
    id("com.gradleup.shadow") version "9.3.1"
}

group = "dev.alexisbinh"
version = "1.2.0"

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
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.cfh.vault:VaultUnlocked:2.19.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("org.xerial:sqlite-jdbc:3.51.3.0")
    compileOnly("com.h2database:h2:2.4.240")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.6.0") {
        exclude(group = "com.google.protobuf") // only needed for X Protocol (mysqlx://), not standard JDBC
    }
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("org.postgresql:postgresql:42.7.10")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.mockito:mockito-junit-jupiter:5.16.1")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("net.cfh.vault:VaultUnlocked:2.19.0")
    testImplementation("me.clip:placeholderapi:2.11.6")
    testRuntimeOnly("com.h2database:h2:2.4.240")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.51.3.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    components {
        withModule<VaultJvmFix>("net.cfh.vault:VaultUnlocked")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "${project.group}.libs.bstats")
    relocate("com.zaxxer.hikari", "${project.group}.libs.hikari")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
