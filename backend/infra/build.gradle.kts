// infra: DynamoDB（データベース）やCognito（ログイン）など、外部サービスとつなぐコードを置くプロジェクト。
// 段階1以降で実装する。domain の約束（インターフェース）を、実際の外部サービスを使って実現する役割。
dependencies {
    // implementation: このプロジェクトが使う（依存する）ライブラリやプロジェクト
    implementation(project(":backend:domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
