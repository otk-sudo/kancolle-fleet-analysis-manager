# backend/

Java（Spring Boot）で作るサーバー側のプログラムです。3つのプロジェクトに分かれています。

| ディレクトリ | 役割 | 依存してよいもの |
|---|---|---|
| `domain/` | 業務ルール（重複判定、抽選、ステータス遷移など） | なし（純粋なJavaのみ） |
| `infra/` | DynamoDB（データベース）などとの接続 | domain |
| `app/` | APIの入口。Spring Bootアプリ本体 | domain、infra |

矢印の向きは `app → infra → domain` の一方向だけです。domain が Spring や AWS を知らないようにしておくと、業務ルールをすばやくテストでき、技術を入れ替えても業務ルールを書き直さずに済みます。

## 最初に読むファイル
1. `domain/src/main/java/.../domain/lottery/WeightedLottery.java`: 抽選の仕組み。コメントで考え方を説明している
2. `domain/src/test/java/.../domain/lottery/WeightedLotteryTest.java`: そのテスト。テストを読むと「何ができるか」がわかる
3. `app/build.gradle.kts`: OpenAPI定義からコードを生成する設定
4. `app/src/main/java/.../fleetanalysis/FleetAnalysisApplication.java`: アプリの起点

## よく使うコマンド（リポジトリの一番上で実行）
```sh
./gradlew build                     # コード生成・コンパイル・テストをすべて実行
./gradlew :backend:domain:test      # domain のテストだけ実行
./gradlew :backend:app:bootRun      # アプリを起動（http://localhost:8080）
```
`./gradlew` は「Gradleラッパー」で、決められたバージョンのGradleを自動で用意して実行します。自分でGradleを入れる必要はありません。

テスト結果は `backend/*/build/reports/tests/test/index.html` をブラウザで開くと見られます。
