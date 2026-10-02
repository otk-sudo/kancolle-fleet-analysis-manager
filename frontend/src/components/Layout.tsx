import { NavLink, Outlet } from 'react-router'

/**
 * 管理画面の共通の枠（上のメニュー）。
 * <Outlet /> の場所に、URLに応じたページ（一覧、詳細など）が入る。
 * NavLink は、今いるページのリンクに自動で active というクラスを付けてくれるリンク。
 */
function Layout() {
  return (
    <div className="app">
      <header className="header">
        <span className="title">艦隊分析 応募者管理</span>
        <nav>
          <NavLink to="/" end>
            応募一覧
          </NavLink>
          <NavLink to="/lottery">抽選</NavLink>
          {/* 配信用画面は配信ソフトで映すため、別のタブで開く */}
          <a href="/stream" target="_blank" rel="noreferrer">
            配信用画面 ↗
          </a>
        </nav>
        <span className="prototype-note">試作版（データは再起動で元に戻ります）</span>
      </header>
      <Outlet />
    </div>
  )
}

export default Layout
