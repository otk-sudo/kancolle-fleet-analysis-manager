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
5. `domain/.../application/ApplicationService.java`: 受付・一覧・ステータス変更など、応募管理の中心
6. `app/.../app/web/ApplicationsController.java`: APIの入口。生成されたインターフェースを実装する例
7. `app/.../app/config/ServiceConfig.java`: 部品（Bean）の組み立て

データの保存先は2種類あり、設定 `app.storage` で切り替えます（`app/.../app/config/ServiceConfig.java`）。
- `memory`（初期値）: メモリに保存する（`infra/.../infra/memory/`）。止めると消える。試作・テスト用
- `dynamodb`: DynamoDB に保存する（`infra/.../infra/dynamodb/`）。本番と、手元の DynamoDB Local 用

DynamoDB Local は、AWSの公式が配っている「自分のPCで動く DynamoDB の代わり」です（公式: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.DownloadingAndRunning.html ）。
Gradle が自動でダウンロードするので、自分で入れる必要はありません。ただし、中で使う部品（SQLite）が Linux（x86_64）・Windows（64bit）・Intel の Mac 用しか配られていないため、Apple シリコン（M1など）の Mac では動きません（その場合はメモリ保存で試してください）。

## よく使うコマンド（リポジトリの一番上で実行）
```sh
./gradlew build                     # コード生成・コンパイル・テストをすべて実行
./gradlew :backend:domain:test      # domain のテストだけ実行
./gradlew :backend:app:bootRun      # アプリを起動（http://localhost:8080）
./gradlew :backend:app:bootRun --args='--spring.profiles.active=demo'  # サンプルデータ入りで起動
./gradlew :backend:infra:runDynamoDbLocal   # DynamoDB Local を起動（ポート8000。データは .local/dynamodb に残る）
./gradlew :backend:app:bootRun --args='--spring.profiles.active=local,demo'  # DynamoDB Local に保存して起動
```
`./gradlew` は「Gradleラッパー」で、決められたバージョンのGradleを自動で用意して実行します。自分でGradleを入れる必要はありません。

テスト結果は `backend/*/build/reports/tests/test/index.html` をブラウザで開くと見られます。
