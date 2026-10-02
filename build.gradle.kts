// 全プロジェクト共通のビルド設定。
// Gradleのビルドスクリプトは Kotlin という言語で書く（拡張子 .kts）。Javaに似た書き方で読める。

subprojects {
    // build.gradle.kts を持つディレクトリだけを対象にする（"backend" のような入れ物のディレクトリは除く）
    if (buildFile.exists()) {
        // Javaのコンパイル・テスト・jar作成などの基本機能を使えるようにする
        apply(plugin = "java")

        group = "io.github.otksudo.fleetanalysis"
        version = "0.1.0"

        // ライブラリをダウンロードする場所。Maven Central は Java の公式ライブラリ置き場
        repositories {
            mavenCentral()
        }

        extensions.configure<JavaPluginExtension> {
            // 「ツールチェーン」: どのバージョンのJavaでビルドするかを固定する。手元とCIで同じ結果になる
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            // 日本語のコメントが文字化けしないようにする
            options.encoding = "UTF-8"
            // メソッドの引数名をコンパイル後も残す（Springが引数名を使って値を当てはめるため）
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            // テストの実行に JUnit 5（JUnit Platform）を使う
            useJUnitPlatform()
        }
    }
}
