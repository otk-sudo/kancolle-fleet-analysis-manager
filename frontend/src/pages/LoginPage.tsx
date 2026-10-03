import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api } from '../api/client'
import { errorMessage, type DevUser } from '../api/types'
import { saveToken } from '../auth/token'
import Notice from '../components/Notice'
import { ROLE_LABELS } from '../labels'

/**
 * ログイン画面（手元で試すための仮ログイン）。
 *
 * 本番では Cognito（AWS のログインのサービス）でメールアドレスとパスワードを入れてログインする（段階4）。
 * 手元にはそれがないので、用意してある「試しユーザー」を選ぶだけでログインできるようにしている。
 * バックエンドが手元用の設定（app.auth.mode: dev）で動いているときだけ使える。
 * TODO(段階4): Cognito のログイン画面に変える
 */
function LoginPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [users, setUsers] = useState<DevUser[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let ignore = false
    void api.GET('/dev/users').then(({ data, error }) => {
      if (ignore) return
      if (data) setUsers(data)
      else setError(errorMessage(error))
    })
    return () => {
      ignore = true
    }
  }, [])

  async function login(user: DevUser) {
    setBusy(true)
    const { data, error } = await api.POST('/dev/login', { body: { userId: user.id } })
    setBusy(false)
    if (!data) {
      setError(errorMessage(error))
      return
    }
    try {
      saveToken(data.token)
    } catch {
      setError('ブラウザにログインの情報を保存できませんでした。ブラウザの設定で、このサイトのデータの保存を許可してください')
      return
    }
    void navigate(safeNext(searchParams.get('next')), { replace: true })
  }

  return (
    <>
      <header className="site-header">
        <div className="site-header-inner">
          <span className="site-title">艦隊分析 応募管理</span>
        </div>
      </header>
      <main className="page narrow-page">
        <h1 className="page-title">ログイン</h1>
        <Notice kind="info" title="手元で試すための仮ログインです">
          使う人を選ぶだけでログインできます。本番ではメールアドレスとパスワード（配信者・運営は二段階認証も）でログインします。
        </Notice>
        {error && (
          <Notice kind="error" title="ログインできませんでした">
            {error}
          </Notice>
        )}
        {users && (
          <ul className="login-users">
            {users.map((user) => (
              <li key={user.id}>
                <button type="button" className="button login-user" disabled={busy} onClick={() => void login(user)}>
                  <strong>{user.displayName}</strong>
                  <span className="tag">{ROLE_LABELS[user.role]}</span>
                  {!user.mfaConfigured && <span className="small sub">二段階認証をまだ設定していない</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
      </main>
    </>
  )
}

/**
 * ログインした後に移る先。URLの next に入っている、元の画面のパスを使う。
 * 「/」で始まるこのサイトの中のパスだけを受け付ける（「//別のサイト」などへ飛ばされないようにするため）。
 * ブラウザは「\」を「/」と同じに読むことがあるので、「\」を含むものも受け付けない。
 */
function safeNext(next: string | null): string {
  const insideThisSite = next !== null && next.startsWith('/') && !next.startsWith('//') && !next.includes('\\')
  if (insideThisSite && !next.startsWith('/login')) {
    return next
  }
  return '/'
}

export default LoginPage
