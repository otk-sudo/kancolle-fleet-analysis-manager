import { useEffect, useMemo, useState } from 'react'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router'
import { api, LOGIN_PATH } from '../api/client'
import { errorMessage, type Me } from '../api/types'
import Notice from '../components/Notice'
import { ROLE_LABELS } from '../labels'
import { clearToken, loadToken } from './token'
import { AuthContext, type Auth } from './useAuth'

/**
 * ログインしている人だけが見られる画面を囲む枠。
 * ・トークンがなければ、ログイン画面へ移る
 * ・トークンがあれば /me で「誰で、何ができるか」を読み、内側の画面に useAuth() で渡す
 * ・二段階認証（MFA）が必要なのにまだ設定していない人には、設定を求める画面を出す
 */
function RequireLogin() {
  const location = useLocation()
  const navigate = useNavigate()
  const hasToken = loadToken() !== null
  // undefined = 読み込み中
  const [me, setMe] = useState<Me | undefined>(undefined)
  // 読み込めなかったときの内容。forbidden は「ログインはできたが、使える役割が付いていない」（403）
  const [failure, setFailure] = useState<{ message: string; forbidden: boolean } | null>(null)

  // /me を読み直すきっかけ。数字を変えると、下の useEffect がもう一度 /me を読む
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    // 次のときに、できることが変わっているかもしれないので /me を読み直す
    // ・このタブに戻ってきたとき（focus）: 配信者が配信の操作を許可した・取り消したときなど
    // ・ほかのタブでログインし直したとき（storage）: localStorage はタブの間で共有なので、送るトークンが変わっている
    const reload = () => setReloadKey((key) => key + 1)
    window.addEventListener('focus', reload)
    window.addEventListener('storage', reload)
    return () => {
      window.removeEventListener('focus', reload)
      window.removeEventListener('storage', reload)
    }
  }, [])

  useEffect(() => {
    if (!hasToken) return
    let ignore = false
    api
      .GET('/me')
      .then(({ data, error, response }) => {
        if (ignore) return
        // 401 のときは、client.ts の共通の処理がログイン画面へ移すので、ここではほかの失敗だけ扱う
        if (data) {
          setMe(data)
          setFailure(null)
        } else {
          setFailure({ message: errorMessage(error), forbidden: response.status === 403 })
        }
      })
      // バックエンドが起動していないなど、応答そのものがないとき
      .catch(() => {
        if (!ignore) setFailure({ message: errorMessage(undefined), forbidden: false })
      })
    return () => {
      ignore = true
    }
  }, [hasToken, reloadKey])

  // useMemo: me が変わらない限り同じ値を使い回す（毎回新しく作ると、読んでいる画面がすべて描き直されるため）
  const auth = useMemo<Auth | null>(() => {
    if (!me) return null
    return {
      me,
      can: (permission) => me.permissions.includes(permission),
      logout: () => {
        clearToken()
        void navigate(LOGIN_PATH)
      },
    }
  }, [me, navigate])

  if (!hasToken) {
    // ログインした後に、見ようとしていた画面へ戻れるよう next に入れる
    const next = location.pathname + location.search
    return <Navigate to={`${LOGIN_PATH}?next=${encodeURIComponent(next)}`} replace />
  }
  if (failure) {
    return (
      <main className="page narrow-page">
        <Notice kind="error" title="ログインしている人の情報を読み込めませんでした">
          {failure.forbidden ? failure.message : `${failure.message}。時間をおいてから、画面を読み込み直してください。`}
        </Notice>
        {/* 別の人でログインし直せるよう、ここにもログアウトを置く（ヘッダーはまだ出ていないため） */}
        <button
          type="button"
          className="button start"
          onClick={() => {
            clearToken()
            void navigate(LOGIN_PATH)
          }}
        >
          ログアウトする
        </button>
      </main>
    )
  }
  if (!auth) {
    return <p className="page">読み込み中…</p>
  }
  if (auth.me.mfaRequired) {
    return <MfaRequired auth={auth} />
  }
  return (
    <AuthContext.Provider value={auth}>
      <Outlet />
    </AuthContext.Provider>
  )
}

/**
 * 二段階認証（MFA）を設定していない配信者・運営に出す画面（仕様 3章）。
 * 配信者と運営はできることが多いので、パスワードが漏れただけでは入れないよう、二段階認証を必ず使ってもらう。
 */
function MfaRequired({ auth }: { auth: Auth }) {
  return (
    <main className="page narrow-page">
      <h1 className="page-title">二段階認証の設定が必要です</h1>
      <Notice kind="info" title={`${auth.me.displayName}（${ROLE_LABELS[auth.me.role]}）でログインしています`}>
        {ROLE_LABELS[auth.me.role]}の人は、二段階認証（パスワードに加えて、スマートフォンのアプリに出る数字も入れるログイン）を
        設定するまで、管理画面を使えません。
      </Notice>
      {/* TODO(段階4): Cognito のログインに変えたら、ここで二段階認証を設定できるようにする */}
      <p>
        今は手元で試すための仮ログインなので、ここでは設定できません。ログアウトして、別の試しユーザーでログインしてください。
      </p>
      <button type="button" className="button primary" onClick={auth.logout}>
        ログアウトする
      </button>
    </main>
  )
}

export default RequireLogin
