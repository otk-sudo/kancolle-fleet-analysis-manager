// Vite の設定。Vite は、開発用サーバー（保存するとすぐ画面に反映される）と本番用のビルドを担当するツール。
// https://vite.dev/config/
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  // React（JSX）を扱えるようにするプラグイン
  plugins: [react()],
  server: {
    // 開発サーバーが応答してよいホスト名。Vite は、知らないホスト名からのアクセスを安全のため拒否する。
    // GitHub Codespaces で開くと https://<codespace名>-5173.app.github.dev のURLになるため、そのドメインを許可する。
    // 先頭の . は「このドメインとその下のすべてのサブドメイン」という意味（https://vite.dev/config/server-options）
    allowedHosts: ['.app.github.dev'],
    // ほかのサイトから開発サーバーを呼べるようにする仕組み（CORS）を切る。
    // Vite は何も書かないと、同じPCで動くほかのアプリの画面（http://localhost:<別のポート>）から、
    // /api を通して応募データを読めてしまうため（https://vite.dev/config/server-options ）
    cors: false,
    // 開発中だけの設定: 画面から /api/... へのリクエストを、手元で動かしているバックエンド（ポート8080）へ転送する。
    // バックエンドのURLには /api が付かないため、転送するときに取り除く。
    // 例: /api/applications → http://127.0.0.1:8080/applications
    // バックエンドは 127.0.0.1 でだけ待ち受けるので、localhost ではなく 127.0.0.1 を書く
    // （localhost は、PCによっては IPv6 の ::1 を先に使い、つながらないことがあるため）。
    // changeOrigin: true にすると、Host ヘッダーを転送先（127.0.0.1:8080）に書き換えて送る。
    // バックエンドは Host が 127.0.0.1 か localhost の要求だけを受け付けるため（LocalAccessFilter）。
    // TODO(段階4): 次のPRで、画面をバックエンドから配信するようにしたら、この転送のしかたを見直す
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
