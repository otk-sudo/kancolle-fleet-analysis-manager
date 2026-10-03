# backend/

Java（Spring Boot）で作るサーバー側のプログラムです。3つのプロジェクトに分かれています。

| ディレクトリ | 役割 | 依存してよいもの |
|---|---|---|
| `domain/` | 業務ルール（重複判定、抽選、ステータス遷移など） | なし（純粋なJavaのみ） |
| `infra/` | データの保存（SQLite） | domain |
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
- `sqlite`（初期値）: SQLite のファイル（`fleet-analysis.db`）に保存する（`infra/.../infra/sqlite/`）。止めても残る。配信者さんのPCで使う形
- `memory`: メモリに保存する（`infra/.../infra/memory/`）。止めると消える。テストと、サンプルデータで画面を試すとき用

SQLite のファイルを置くフォルダは、設定 `app.data-dir`（環境変数 `APP_DATA_DIR`）で決めます。指定しないと、Windows では利用者ごとのデータのフォルダ（`%LOCALAPPDATA%\KancolleFleetAnalysis`）、Mac・Linux ではホームフォルダの `KancolleFleetAnalysis` になります。
表の形は `infra/src/main/resources/db/migration/` の SQL ファイルで決めていて、起動したときに Flyway が、まだ当てていないファイルだけを実行します（くわしくは docs/design.md 2章）。
SQLite を Java から使う部品（sqlite-jdbc）には Windows・Mac（Intel と Apple シリコン）・Linux 用の部品が入っているので、どのPCでも自分で何かを入れる必要はありません。

## よく使うコマンド（リポジトリの一番上で実行）
```sh
./gradlew build                     # コード生成・コンパイル・テストをすべて実行
./gradlew :backend:domain:test      # domain のテストだけ実行
./gradlew :backend:app:bootRun      # アプリを起動（http://127.0.0.1:8080。このPCの中からだけ開ける。データは上のフォルダの SQLite に残る）
APP_STORAGE=memory ./gradlew :backend:app:bootRun --args='--spring.profiles.active=demo'  # メモリに置き、サンプルデータ入りで起動
```
`./gradlew` は「Gradleラッパー」で、決められたバージョンのGradleを自動で用意して実行します。自分でGradleを入れる必要はありません。

テスト結果は `backend/*/build/reports/tests/test/index.html` をブラウザで開くと見られます。
