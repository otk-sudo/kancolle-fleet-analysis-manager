// app: Spring Bootのアプリ本体。APIの入口（コントローラー）を置き、domain と infra を組み合わせて動かす。

plugins {
    // Spring Bootのプラグイン: 起動できるjarの作成や、ライブラリのバージョン管理をしてくれる
    alias(libs.plugins.spring.boot)
    // OpenAPI Generatorのプラグイン: api/openapi.yaml からJavaのコードを自動生成する
    alias(libs.plugins.openapi.generator)
}

// 自動生成したコードの置き場所（build/ の下なので Git には入らない）
val generatedDir = layout.buildDirectory.dir("generated/openapi")

// OpenAPI定義からのコード生成の設定。
// 生成されるのは「APIの形（URL、引数、戻り値）を決めたインターフェース」と「データのクラス」。
// 私たちはそのインターフェースを implements して中身を書くだけでよい。
// 定義を変えると生成コードも変わるので、定義と実装のずれをコンパイルエラーで気づける。
openApiGenerate {
    generatorName.set("spring") // Spring用のコードを生成する
    inputSpec.set(rootProject.file("api/openapi.yaml").absolutePath)
    outputDir.set(generatedDir.get().asFile.absolutePath)
    // 生成のたびに出力先を空にする。定義から消したAPIやデータのクラスが古いまま残り、
    // 手元だけコンパイルが通ってしまうのを防ぐ
    cleanupOutput.set(true)
    apiPackage.set("io.github.otksudo.fleetanalysis.app.api")
    modelPackage.set("io.github.otksudo.fleetanalysis.app.api.model")
    configOptions.set(mapOf(
        "interfaceOnly" to "true", // インターフェースだけを生成する（中身は自分で書く）
        "useSpringBoot4" to "true",
        "useJakartaEe" to "true", // 新しい名前空間（jakarta.*）を使う。Spring Boot 3以降の決まり
        "useTags" to "true", // OpenAPIのタグごとにインターフェースを分ける（IntakeApi, ApplicationsApi など）
        "openApiNullable" to "false",
        "skipDefaultInterface" to "true", // 未実装のメソッドがあればコンパイルエラーにする
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "dateLibrary" to "java8", // 日付は java.time（LocalDate など）を使う
        "hideGenerationTimestamp" to "true", // 生成日時を書き込まない（毎回差分が出ないように）
        "generateJsonIncludeAnnotations" to "false",
        "generateJsonSetterNullsAnnotations" to "false",
    ))
}

// 生成したコードも、普通のソースコードと同じようにコンパイル対象にする
sourceSets {
    main {
        java.srcDir(generatedDir.map { it.dir("src/main/java") })
    }
}

// コンパイルの前に必ずコード生成を行う
tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

dependencies {
    // Spring Bootが推奨するバージョンの組み合わせを使う（個別にバージョンを書かなくてよくなる）
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":backend:domain"))
    implementation(project(":backend:infra"))
    // Web API を作るための一式（Spring MVC と組み込みWebサーバー）。
    // Spring Boot 4 で spring-boot-starter-web から名前が変わった
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation") // 入力値チェック（@NotNull など）

    testImplementation("org.springframework.boot:spring-boot-starter-test") // Spring用のテスト一式
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test") // APIのテスト（MockMvc など）
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
