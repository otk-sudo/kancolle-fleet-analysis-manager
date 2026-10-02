// APIを呼び出すためのクライアント。
//
// openapi-fetch は、OpenAPI定義から生成した型（schema.gen.ts）を使って、
// URL・引数・戻り値の型をチェックしてくれる fetch の薄いラッパー。
// 例: api.GET('/applications/{applicationId}', { params: { path: { applicationId: 'xxx' } } })
// 存在しないURLや間違った引数を書くと、実行する前にエディタ上でエラーになる。
//
// schema.gen.ts は `npm run generate:api` で api/openapi.yaml から自動生成する（Gitには入れない）。
import createClient from 'openapi-fetch'
import type { paths } from './schema.gen'

export const api = createClient<paths>({
  // 接続先のURL。環境変数 VITE_API_BASE_URL があればそれを使う（.env ファイルで設定する）
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
})
