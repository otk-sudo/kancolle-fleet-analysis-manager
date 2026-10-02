// DynamoDB・Cognitoなど外部サービスとの接続（段階1以降で実装）
dependencies {
    implementation(project(":backend:domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
