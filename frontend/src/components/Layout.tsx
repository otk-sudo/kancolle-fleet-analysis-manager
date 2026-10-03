import { NavLink, Outlet, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { ROLE_LABELS } from '../labels'

/**
 * 管理画面の共通の枠（上のヘッダーと、ページの中身を入れる場所）。
 * <Outlet /> の場所に、URLに応じたページ（一覧、詳細など）が入る。
 * NavLink は、今いるページのリンクに自動で active というクラスを付けてくれるリンク。
 */
function Layout() {
  // 応募の詳細（/applications/...）を見ているときも、メニューは「応募一覧」を今いる場所として示す
  const { pathname } = useLocation()
  const { me, logout } = useAuth()
  const inApplications = pathname === '/' || pathname.startsWith('/applications/')
  return (
    <>
      <header className="site-header">
        <div className="site-header-inner">
          <span className="site-title">艦隊分析 応募管理</span>
          <nav aria-label="メインメニュー" className="site-nav">
            <NavLink
              to="/"
              end
              // className に関数を渡すと、active を付けるかどうかを自分で決められる
              className={({ isActive }) => (isActive || inApplications ? 'active' : undefined)}
            >
              応募一覧
            </NavLink>
            <NavLink to="/lottery">抽選</NavLink>
            {/* TODO(段階7): 取り込み（連携に失敗した回答・CSVの取り込み）の画面を足す */}
            <NavLink to="/settings">設定</NavLink>
          </nav>
          <div className="site-header-side">
            {/*
              TODO(段階5): 配信用画面（表示専用）と配信の操作パネルを分けたら、ここは「配信の操作パネルを開く」にする。
              配信ソフトで映すので、別のタブで開く
            */}
            <a className="button" href="/stream" target="_blank" rel="noreferrer">
              配信用画面を開く
            </a>
            {/* ログインしている人の名前と役割（例: どうぺん（配信者）） */}
            <span className="site-user">
              {me.displayName}（{ROLE_LABELS[me.role]}）
            </span>
            <button type="button" className="button" onClick={logout}>
              ログアウト
            </button>
          </div>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}

export default Layout
