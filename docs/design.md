# 設計メモ（開発準備）

仕様: [spec.md](spec.md) v1.0 にもとづく。

## 1. リポジトリ構成（モノレポ）

```
/
├─ api/                 OpenAPI定義（openapi.yaml）。ここが唯一の正
├─ backend/             Java 21 / Spring Boot 4 / Gradle
│  ├─ app/              Lambdaのエントリポイント、API層（生成インターフェースの実装）
│  ├─ domain/           業務ロジック（重複判定、条件ルール、抽選、ステータス遷移）。外部依存なし
│  └─ infra/            DynamoDBアクセス、Cognito連携
├─ frontend/            React + TypeScript（Vite）。APIクライアントはopenapi.yamlから生成
├─ infra/               AWS CDK（Java）
├─ gas/                 Googleフォーム連携のApps Script
├─ docs/                仕様書・設計メモ
└─ .github/workflows/   CI
```

- Gradleはマルチプロジェクト（backend/app, backend/domain, backend/infra, infra）
- サーバー側の生成: openapi-generator の Spring インターフェース生成（`interfaceOnly`）
- 画面側の生成: openapi-typescript ＋ openapi-fetch
- domain は Spring にも AWS にも依存させず、単体テストで中身を固める

## 2. データモデル（DynamoDB 単一テーブル）

テーブル名: `Main`。キーは `PK` / `SK`、検索用に GSI1・GSI2。

| エンティティ | PK | SK | 主な属性 |
|---|---|---|---|
| 応募 | `APP#<applicationId>` | `META` | xId（正規化済み）、提督名、匿名希望、シミュレータURL、回答（formVersion付きのmap）、ステータス、並び順キー、配信日、メモ、印（重複/再応募/条件外と理由）、受付日時 |
| 応募者 | `APPLICANT#<xId>` | `META` | 最終当選日時、最後の当選以降の落選回数、応募数 |
| 応募者の履歴 | `APPLICANT#<xId>` | `APP#<受付日時>#<applicationId>` | 一覧表示用の要約 |
| ステータス履歴 | `APP#<applicationId>` | `STATUS#<日時>` | 変更前、変更後、実行者 |
| 抽選記録 | `LOTTERY#<lotteryId>` | `META` | 日時、実行者、方式（まとめ/配信中）、当選人数、乱数の種 |
| 抽選の対象者 | `LOTTERY#<lotteryId>` | `ENTRY#<applicationId>` | 当たりやすさ、結果 |
| 設定 | `SETTINGS` | `<種類>`（OPTIONS, STATUSES, RULES, LOTTERY） | 設定内容、版 |
| 操作履歴 | `AUDIT#<yyyy-mm>` | `<日時>#<id>` | 操作種別、対象、実行者 |

GSI:
- GSI1（ステータス別の並び）: `GSI1PK = STATUS#<ステータス>`、`GSI1SK = <並び順キー>`。次に分析する人・一覧の絞り込みに使う
- GSI2（受付順）: `GSI2PK = APPS`、`GSI2SK = <受付日時>#<applicationId>`

並び順キー: 受付時は受付日時から作り、手動で並べ替えたら前後のキーの間の値を入れる（全件の振り直しが不要）。

規模の想定: 応募は数百〜数千件。一覧は1回のクエリで取れる範囲に収まる。

## 3. API（概要）

詳細は [openapi.yaml](../api/openapi.yaml)。

| メソッド | パス | 役割 | 権限 |
|---|---|---|---|
| POST | /intake/applications | フォームからの応募受付 | フォーム用秘密キー |
| GET | /applications | 一覧（絞り込み、次に分析する順） | 全員 |
| GET | /applications/{id} | 詳細 | 全員 |
| PATCH | /applications/{id} | ステータス・配信日・メモの変更 | 配信者・運営 |
| PUT | /applications/{id}/position | 並べ替え | 配信者・運営 |
| GET | /applicants/{xId}/applications | 同じ応募者の履歴 | 全員 |
| DELETE | /applicants/{xId} | 削除依頼への対応 | 運営 |
| GET | /stream/current | 配信用画面の表示内容（いま分析中の1人） | 全員 |
| POST | /lotteries | 抽選の実行（まとめ／配信中） | 配信者・運営 |
| GET | /lotteries | 抽選記録 | 全員 |
| GET/PUT | /settings/{kind} | 設定の取得・変更 | 取得は全員、変更は配信者・運営 |
| GET/POST/DELETE | /users | ユーザー管理 | 配信者（関係者のみ）・運営 |
| POST | /imports/csv | 旧スプレッドシートの取り込み | 運営 |

## 4. 業務ロジックの要点

- 重複・再応募の判定は受付時に1回行い、印として保存する（判定ルールを変えたら再判定を実行できる）
- 抽選の重み: `weight = 1 + 落選回数 × 補正の強さ`（補正オフなら常に1）。重み付き非復元抽出
- 乱数の種は抽選ごとに生成して記録する。同じ種と対象者なら同じ結果が再現できる
- ステータス遷移は表で定義し、許されない遷移はエラーにする

## 5. 開発の進め方（段階）

| 段階 | 内容 | できるようになること |
|---|---|---|
| 0. 土台 | リポジトリ、Gradleマルチプロジェクト、openapi.yaml、コード生成、CI | ビルドとテストが自動で回る |
| 1. 受付と一覧 | 受付API、Apps Script、一覧・詳細API、DynamoDB、ローカル実行（DynamoDB Local） | フォームの応募がツールに入って一覧で見える |
| 2. 進捗管理 | ステータス変更、並べ替え、重複・再応募の印、履歴比較 | 課題1・2が解決する |
| 3. 画面とログイン | React管理画面のデザインの作り込み（画面の見た目・使いやすさ。配信者さんの意見を反映）、Cognito、権限 | 配信者・関係者が使い始められる |
| 4. AWS公開 | CDKでLambda/API Gateway/DynamoDB/S3/CloudFront/Cognito、Budgets | 本番運用できる |
| 5. 配信用画面 | 配信用画面のデザインの作り込み（配信に映したときの見栄え）、匿名表示 | 配信で使える |
| 6. 抽選 | まとめ抽選、配信中の抽選、落選補正、記録 | 応募が多くても回せる |
| 7. 設定と取り込み | 条件ルール、選択肢、ユーザー管理、CSV取り込み | 運用を配信者さんに任せられる |

各段階で動くものを作り、段階1〜3あたりで一度配信者さんに見てもらう。

試作版（PR #2）の画面は動きを確かめるための仮のデザインで、見た目は段階3（管理画面）と段階5（配信用画面）で作り込む。
