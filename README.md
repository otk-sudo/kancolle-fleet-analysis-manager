# kancolle-fleet-analysis-manager

艦隊分析への応募を管理するWebツール。Googleフォームで受け付けた応募を取り込み、進捗管理・重複チェック・抽選・配信用表示を行う。

- **初めて読む人へ: [docs/guide/はじめに.md](docs/guide/はじめに.md)**（わからない言葉は [用語集](docs/guide/用語集.md)）
- 仕様書: [docs/spec.md](docs/spec.md)
- 設計メモと開発の段階: [docs/design.md](docs/design.md)
- 開発ルール: [docs/development.md](docs/development.md)
- API定義: [api/openapi.yaml](api/openapi.yaml)（バックエンドと画面の型はここから生成する）

## 構成

| ディレクトリ | 内容 |
|---|---|
| `api/` | OpenAPI定義 |
| `backend/domain/` | 業務ロジック（重複判定、条件ルール、抽選など）。SpringやAWSに依存しない |
| `backend/infra/` | DynamoDB・Cognitoとの接続 |
| `backend/app/` | Spring Boot アプリ。OpenAPIから生成したインターフェースを実装する |
| `frontend/` | React + TypeScript の管理画面・配信用画面 |
| `gas/` | Googleフォーム連携用の Apps Script |

## 必要なもの

- Java 21
- Node.js 22

## 試作版を動かす

```sh
./scripts/dev.sh   # サンプルデータ入りで起動し、http://localhost:5173 を開く
```

GitHub Codespaces でも動きます（`.devcontainer/` に設定あり）。手順は [はじめに](docs/guide/はじめに.md) の5章。

## よく使うコマンド

```sh
# バックエンドのビルドとテスト（OpenAPIからのコード生成も行う）
./gradlew build

# 画面の開発サーバー
cd frontend
npm install
npm run dev

# 画面のビルド（APIの型生成、型チェックを含む）
npm run build

# OpenAPI定義のチェック
npx @redocly/cli@2 lint api/openapi.yaml
```
