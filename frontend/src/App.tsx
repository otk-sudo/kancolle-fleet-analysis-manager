import { BrowserRouter, Route, Routes } from 'react-router'
import Layout from './components/Layout'
import ApplicationDetailPage from './pages/ApplicationDetailPage'
import ApplicationListPage from './pages/ApplicationListPage'
import LotteryPage from './pages/LotteryPage'
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
        {/* 管理画面: 上にメニューがある共通の枠（Layout）の中に各ページを出す */}
        <Route element={<Layout />}>
          <Route path="/" element={<ApplicationListPage />} />
          <Route path="/applications/:applicationId" element={<ApplicationDetailPage />} />
          <Route path="/lottery" element={<LotteryPage />} />
        </Route>
        {/* 配信用画面: 配信に映すので、メニューのない画面にする */}
        <Route path="/stream" element={<StreamPage />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
