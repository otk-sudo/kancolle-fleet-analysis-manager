// 画面の起点。index.html の <div id="root"> の中に、React で作った画面を描画する。
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  // StrictMode: 開発中だけ、よくある間違いを見つけるための追加チェックを行う（本番の動きには影響しない）
  <StrictMode>
    <App />
  </StrictMode>,
)
