// Vite の設定。Vite は、開発用サーバー（保存するとすぐ画面に反映される）と本番用のビルドを担当するツール。
// https://vite.dev/config/
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  // React（JSX）を扱えるようにするプラグイン
  plugins: [react()],
  server: {
    // 開発中だけの設定: 画面から /api/... へのリクエストを、手元で動かしているバックエンド（ポート8080）へ転送する。
    // バックエンドのURLには /api が付かないため、転送するときに取り除く。
    // 例: /api/applications → http://localhost:8080/applications
    // 本番では API Gateway の設定で同じことを行う（段階4）
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
