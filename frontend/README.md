# frontend/

管理画面と配信用画面を作る、React + TypeScript のプロジェクトです。

## 使っている技術
| 技術 | 何をするものか |
|---|---|
| React | 画面を「コンポーネント」という部品の組み合わせで作るライブラリ |
| TypeScript | JavaScriptに「型」を足した言語。間違いを実行前に見つけられる |
| Vite | 開発用サーバーと本番用ビルドのツール。保存するとすぐ画面に反映される |
| openapi-typescript | `api/openapi.yaml` からAPIの型を自動生成する |
| openapi-fetch | 生成した型を使って、型チェック付きでAPIを呼び出す |
| oxlint | よくある書き間違いを見つけるツール（リンター） |

## 最初に読むファイル
1. `src/main.tsx`: 画面の起点
2. `src/App.tsx`: 画面全体の一番外側のコンポーネント
3. `src/api/client.ts`: APIの呼び出し方

## よく使うコマンド（このディレクトリで実行）
```sh
npm install          # 最初に1回。必要なライブラリを入れる
npm run dev          # 開発用サーバーを起動（表示されたURLをブラウザで開く）
npm run generate:api # OpenAPI定義から型を生成（build と typecheck でも自動で行う）
npm run typecheck    # 型チェック
npm run lint         # リンター
npm run build        # 本番用にビルド（dist/ に出力）
```
