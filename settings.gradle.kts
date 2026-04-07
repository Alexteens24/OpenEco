plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "OpenEco"

if (file("stress-addon").exists()) {
	include("stress-addon")
}

if (file("enhancements-addon").exists()) {
	include("enhancements-addon")
}

if (file("proxy-addon").exists()) {
	include("proxy-addon")
}
