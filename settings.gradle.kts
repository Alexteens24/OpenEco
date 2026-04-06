rootProject.name = "SimpleEco"

if (file("stress-addon").exists()) {
	include("stress-addon")
}

if (file("enhancements-addon").exists()) {
	include("enhancements-addon")
}
