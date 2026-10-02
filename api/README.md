# api/

APIの定義ファイル `openapi.yaml` を置く場所です。

## OpenAPIとは
Web APIの「どのURLに、どんなデータを送ると、どんなデータが返ってくるか」を書くための標準の書式です。YAML（インデントで構造を表すテキスト形式）で書きます。

このプロジェクトでは、この定義を**唯一の正**として扱います。

- バックエンド（Java）: 定義からインターフェースを自動生成し、それを実装する（`backend/app`）
- 画面（TypeScript）: 定義から型を自動生成し、API呼び出しの型チェックに使う（`frontend/src/api`）

APIを変えたいときは、まずこのファイルを書き換えます。生成されたコードを手で直してはいけません。

## 読み方のヒント
- `paths:` の下がURLの一覧。`get:` `post:` などがHTTPメソッド
- `components: schemas:` の下がデータの形（クラスのようなもの）
- `$ref: '#/components/schemas/Application'` は「Applicationの定義を参照する」という意味

## チェック方法
```sh
npx @redocly/cli@2 lint api/openapi.yaml
```
ブラウザで見やすく表示したいときは `npx @redocly/cli@2 preview-docs api/openapi.yaml` が便利です。
