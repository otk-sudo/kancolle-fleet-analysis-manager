import { BrowserRouter, Route, Routes } from 'react-router'
import RequireLogin from './auth/RequireLogin'
import Layout from './components/Layout'
import ApplicationDetailPage from './pages/ApplicationDetailPage'
import ApplicationListPage from './pages/ApplicationListPage'
import LoginPage from './pages/LoginPage'
import LotteryPage from './pages/LotteryPage'
import SettingsPage from './pages/SettingsPage'
import StreamPage from './pages/StreamPage'

/**
 * 画面全体の一番外側のコンポーネント。URLに応じて表示するページを切り替える（ルーティング）。
 *
 * React Router を使っている。<Route path="..." element={...}> で「このURLならこのページ」を決める。
 * ページを切り替えてもサーバーにページを取りに行かず、画面の中だけで描き直すので速い。
 */
function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* ログイン画面: ログインしていなくても見られる */}
        <Route path="/login" element={<LoginPage />} />
        {/* ここから内側は、ログインしている人だけが見られる（RequireLogin が確かめる） */}
        <Route element={<RequireLogin />}>
          {/* 管理画面: 上にメニューがある共通の枠（Layout）の中に各ページを出す */}
          <Route element={<Layout />}>
            <Route path="/" element={<ApplicationListPage />} />
            <Route path="/applications/:applicationId" element={<ApplicationDetailPage />} />
            <Route path="/lottery" element={<LotteryPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
          {/* 配信用画面: 配信に映すので、メニューのない画面にする */}
          {/* TODO(段階5): 配信に映す画面は、ログインの代わりに閲覧専用のURLで見られるようにする（仕様 7.2） */}
          <Route path="/stream" element={<StreamPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
