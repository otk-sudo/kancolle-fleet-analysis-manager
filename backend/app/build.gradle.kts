plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
}

val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(rootProject.file("api/openapi.yaml").absolutePath)
    outputDir.set(generatedDir.get().asFile.absolutePath)
    apiPackage.set("io.github.otksudo.fleetanalysis.app.api")
    modelPackage.set("io.github.otksudo.fleetanalysis.app.api.model")
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot4" to "true",
        "useJakartaEe" to "true",
        "useTags" to "true",
        "openApiNullable" to "false",
        "skipDefaultInterface" to "true",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "dateLibrary" to "java8",
        "hideGenerationTimestamp" to "true",
        "generateJsonIncludeAnnotations" to "false",
        "generateJsonSetterNullsAnnotations" to "false",
    ))
}

sourceSets {
    main {
        java.srcDir(generatedDir.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":backend:domain"))
    implementation(project(":backend:infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
