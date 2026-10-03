// domain: 業務ロジック（重複判定、条件ルール、抽選、ステータス遷移）を置くプロジェクト。
// SpringやAWSなどには依存させない。そうすることで、
//   - 技術を入れ替えても業務ロジックを書き直さずに済む
//   - 起動に時間のかかる仕組みなしで、テストをすばやく実行できる
dependencies {
    // testImplementation: テストのときだけ使うライブラリ
    // platform(...): 「BOM」と呼ばれる、関連ライブラリのバージョンをそろえるための指定
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params) // 同じテストを入力を変えて何度も実行する機能
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
