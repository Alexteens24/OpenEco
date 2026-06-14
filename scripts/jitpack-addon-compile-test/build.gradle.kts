/*
 * Standalone compile check: enhancements-addon sources against JitPack API only.
 * Run from repo root:
 *   ./gradlew -p scripts/jitpack-addon-compile-test compileJava -PjitpackVersion=v1.4.6
 */

plugins {
    java
}

val jitpackVersion = findProperty("jitpackVersion")?.toString()
    ?: error("Pass -PjitpackVersion=<tag-or-commit>, e.g. -PjitpackVersion=v1.4.4")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.github.Alexteens24:OpenEco:$jitpackVersion")
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

sourceSets {
    main {
        java {
            srcDir("../../enhancements-addon/src/main/java")
            include(
                "dev/alexisbinh/openeco/enhancements/exchange/ExchangeCommand.java",
                "dev/alexisbinh/openeco/enhancements/interest/InterestTask.java",
            )
        }
    }
}

tasks.compileJava {
    options.compilerArgs.add("-parameters")
}
