// Vite の設定。Vite は、開発用サーバー（保存するとすぐ画面に反映される）と本番用のビルドを担当するツール。
// https://vite.dev/config/
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  // React（JSX）を扱えるようにするプラグイン
  plugins: [react()],
})
