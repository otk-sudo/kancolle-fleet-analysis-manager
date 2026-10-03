import { NavLink, Outlet, useLocation } from 'react-router'

/**
 * 管理画面の共通の枠（上のヘッダーと、ページの中身を入れる場所）。
 * <Outlet /> の場所に、URLに応じたページ（一覧、詳細など）が入る。
 * NavLink は、今いるページのリンクに自動で active というクラスを付けてくれるリンク。
 */
function Layout() {
  // 応募の詳細（/applications/...）を見ているときも、メニューは「応募一覧」を今いる場所として示す
  const { pathname } = useLocation()
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
            {/* TODO(段階5): フォームの取り込みの画面を足す（CSVの取り込みは段階9） */}
            <NavLink to="/settings">設定</NavLink>
          </nav>
          <div className="site-header-side">
            {/*
              TODO(段階7): 配信用画面（表示専用）と配信の操作パネルを分けたら、ここは「配信の操作パネルを開く」にする。
              配信ソフトで映すので、別のタブで開く
            */}
            <a className="button" href="/stream" target="_blank" rel="noreferrer">
              配信用画面を開く
            </a>
            {/* TODO(段階4): 次のPRで SQLite に保存するようになったら、この表示を消す */}
            <span className="site-user">試作版（データは再起動で元に戻ります）</span>
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
