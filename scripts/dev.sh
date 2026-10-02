#!/usr/bin/env bash
# 試作版をまとめて起動するスクリプト。
#   1. バックエンド（Spring Boot、ポート8080）を、サンプルデータ入り（demo プロファイル）で起動する
#   2. バックエンドが応答するまで待つ
#   3. 画面（Vite の開発サーバー、ポート5173）を起動する
# 止めるときは Ctrl+C。画面と一緒にバックエンドも止まる。
#
# 使い方（リポジトリの一番上のフォルダで）: ./scripts/dev.sh

# set -e: どれかのコマンドが失敗したら、そこで止める
set -euo pipefail

# このスクリプトがある場所の1つ上（リポジトリの一番上）へ移動する。どこから実行しても同じ動きにするため
cd "$(dirname "$0")/.."

echo "バックエンドを起動しています（初回は依存ライブラリのダウンロードで数分かかることがあります）..."
./gradlew :backend:app:bootRun --args='--spring.profiles.active=demo' > backend.log 2>&1 &
BACKEND_PID=$!

# trap: このスクリプトが終わるとき（Ctrl+C を含む）に、バックエンドも止める
trap 'echo "バックエンドを止めています..."; kill "$BACKEND_PID" 2>/dev/null || true' EXIT

# バックエンドが応答するまで、1秒ごとに確かめる（最大5分）
for _ in $(seq 1 300); do
  if curl -fs http://localhost:8080/applications > /dev/null; then
    echo "バックエンドが起動しました。ログは backend.log にあります"
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "バックエンドの起動に失敗しました。backend.log を確認してください" >&2
    exit 1
  fi
  sleep 1
done

echo "画面を起動します。表示された http://localhost:5173 を開いてください"
cd frontend
if [ ! -d node_modules ]; then
  npm ci
fi
npm run generate:api
npx vite
