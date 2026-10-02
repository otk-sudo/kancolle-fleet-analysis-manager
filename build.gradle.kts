subprojects {
    if (buildFile.exists()) {
        apply(plugin = "java")

        group = "io.github.otksudo.fleetanalysis"
        version = "0.1.0"

        repositories {
            mavenCentral()
        }

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
