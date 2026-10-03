// APIを呼び出すためのクライアント。
//
// openapi-fetch は、OpenAPI定義から生成した型（schema.gen.ts）を使って、
// URL・引数・戻り値の型をチェックしてくれる fetch の薄いラッパー。
// 例: api.GET('/applications/{applicationId}', { params: { path: { applicationId: 'xxx' } } })
// 存在しないURLや間違った引数を書くと、実行する前にエディタ上でエラーになる。
//
// schema.gen.ts は `npm run generate:api` で api/openapi.yaml から自動生成する（Gitには入れない）。
import createClient, { type Middleware } from 'openapi-fetch'
import { clearToken, loadToken } from '../auth/token'
import type { paths } from './schema.gen'

export const api = createClient<paths>({
  // 接続先のURL。環境変数 VITE_API_BASE_URL があればそれを使う（.env ファイルで設定する）。
  // ない場合は /api に送る。開発中は vite.config.ts の proxy 設定でバックエンドへ転送される
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
})

/** ログイン画面のURL。ログインが切れたときに、ここへ移る */
export const LOGIN_PATH = '/login'

/**
 * すべてのAPI呼び出しに共通でかける処理（ミドルウェア）。
 * ・送る前: 保存しているトークンを「Authorization: Bearer トークン」というヘッダーに付ける
 * ・受け取った後: 401（ログインしていない・期限切れ）なら、トークンを捨ててログイン画面へ移る
 * こうしておけば、各画面でトークンのことを気にせずに api.GET などを書ける。
 */
const authMiddleware: Middleware = {
  onRequest({ request, schemaPath }) {
    const token = loadToken()
    // 仮ログインのAPI（/dev/...）はログインする前に呼ぶので、トークンを付けない。
    // 古いトークン（期限切れなど）を付けると断られ、ログインし直せなくなるため
    if (token && !schemaPath.startsWith('/dev/')) {
      request.headers.set('Authorization', `Bearer ${token}`)
    }
    return request
  },
  onResponse({ response }) {
    if (response.status === 401) {
      // 使えないトークンは捨てる。ログイン画面にいるときは、移らずにそのまま選び直してもらう
      clearToken()
      if (window.location.pathname === LOGIN_PATH) {
        return response
      }
      // ログインした後に元の画面へ戻れるよう、今のURLを next に入れておく
      const next = window.location.pathname + window.location.search
      window.location.assign(`${LOGIN_PATH}?next=${encodeURIComponent(next)}`)
    }
    return response
  },
}

api.use(authMiddleware)
