// infra: データの保存先（SQLite）とつなぐコードを置くプロジェクト。
// domain の約束（インターフェース）を、実際のデータベースを使って実現する役割。

dependencies {
    // implementation: このプロジェクトが使う（依存する）ライブラリやプロジェクト
    implementation(project(":backend:domain"))
    // platform(BOM): Spring Boot が「組み合わせて動く」と確かめたバージョンをそろえて使う。
    // そのため、下のライブラリにはバージョンを書いていない
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    // SQLite を Java から使うための部品（JDBC ドライバー）。SQLite 本体も中に入っているので、PCに別に入れなくてよい
    implementation("org.xerial:sqlite-jdbc")
    // Flyway: データベースの表を、決まった順番のSQLファイル（src/main/resources/db/migration）で作る・変える道具
    implementation("org.flywaydb:flyway-core")
    // Spring の JDBC の部品。SQL を短く安全に書ける JdbcClient と、トランザクションの仕組みを使う
    implementation("org.springframework:spring-jdbc")
    // JSON の読み書き（フォームの回答や印のように、形が決まっていない値を1つの列に入れるため）
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
