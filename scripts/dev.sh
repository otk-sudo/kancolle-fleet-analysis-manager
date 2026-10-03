#!/usr/bin/env bash
# 試作版をまとめて起動するスクリプト。
#   1. バックエンド（Spring Boot、ポート8080）を、サンプルデータ入り（demo プロファイル）で起動する
#   2. バックエンドが応答するまで待つ
#   3. 画面（Vite の開発サーバー、ポート5173）を起動する
# 止めるときは Ctrl+C。画面と一緒にバックエンドも止まる。
#
# 使い方（リポジトリの一番上のフォルダで）:
#   ./scripts/dev.sh      データをメモリに置く（止めると消え、起動のたびにサンプルデータに戻る）
#   ./scripts/dev.sh db   データを手元の DynamoDB Local に置く（止めても残る。最初の1回だけサンプルデータが入る）

# set -e: どれかのコマンドが失敗したら、そこで止める
set -euo pipefail

# このスクリプトがある場所の1つ上（リポジトリの一番上）へ移動する。どこから実行しても同じ動きにするため
cd "$(dirname "$0")/.."

# ${1:-}: 1つ目の引数（なければ空）。「db」なら DynamoDB Local を使う
PROFILES=demo
DYNAMODB_PID=""
BACKEND_PID=""
# trap: このスクリプトが終わるとき（Ctrl+C を含む）に、裏で動かしたものも止める
trap 'echo "バックエンドを止めています..."; kill $BACKEND_PID $DYNAMODB_PID 2>/dev/null || true' EXIT

if [ "${1:-}" = "db" ]; then
  PROFILES=local,demo
  echo "DynamoDB Local を起動しています（データは .local/dynamodb に保存されます。ログは dynamodb.log）..."
  ./gradlew :backend:infra:runDynamoDbLocal > dynamodb.log 2>&1 &
  DYNAMODB_PID=$!
  # ポート8000に接続できるまで待つ（DynamoDB Local は中身のない問い合わせにエラーを返すので、-f は付けない）
  db_started=false
  for _ in $(seq 1 300); do
    if curl -s -o /dev/null http://localhost:8000; then
      db_started=true
      break
    fi
    if ! kill -0 "$DYNAMODB_PID" 2>/dev/null; then
      echo "DynamoDB Local の起動に失敗しました。dynamodb.log を確認してください" >&2
      exit 1
    fi
    sleep 1
  done
  if [ "$db_started" != true ]; then
    echo "5分待っても DynamoDB Local が応答しませんでした。dynamodb.log を確認してください" >&2
    exit 1
  fi
fi

echo "バックエンドを起動しています（初回は依存ライブラリのダウンロードで数分かかることがあります）..."
# 画面は Vite の開発サーバー（ポート5173）から開くので、そこからの変更の要求も受け付けるように伝える。
# バックエンドは、ほかのサイトからの変更を断る（Origin の確認。backend の LocalAccessFilter）ため
export APP_EXTRA_ORIGINS="http://localhost:5173,http://127.0.0.1:5173"
# GitHub Codespaces では、画面のURLが https://<codespace名>-5173.<転送用のドメイン> になるので、それも足す。
# CODESPACE_NAME などは Codespaces が用意する環境変数（説明のページ:
# https://docs.github.com/en/codespaces/developing-in-a-codespace/default-environment-variables-for-your-codespace ）。
# 変数の名前は、作業した環境から公式ページを開けず、まだ確かめていない（未確認）。
# 名前が違って足せなかったときは、画面から変更すると「ほかのWebサイトからの操作は受け付けません」と出る
if [ -n "${CODESPACE_NAME:-}" ] && [ -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]; then
  APP_EXTRA_ORIGINS="$APP_EXTRA_ORIGINS,https://${CODESPACE_NAME}-5173.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
fi
./gradlew :backend:app:bootRun --args="--spring.profiles.active=$PROFILES" > backend.log 2>&1 &
BACKEND_PID=$!

# バックエンドが応答するまで、1秒ごとに確かめる（最大5分）
# バックエンドは 127.0.0.1 でだけ待ち受けるので、localhost ではなく 127.0.0.1 に問い合わせる
started=false
for _ in $(seq 1 300); do
  if curl -fs http://127.0.0.1:8080/applications > /dev/null; then
    echo "バックエンドが起動しました。ログは backend.log にあります"
    started=true
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "バックエンドの起動に失敗しました。backend.log を確認してください" >&2
    exit 1
  fi
  sleep 1
done
if [ "$started" != true ]; then
  echo "5分待ってもバックエンドが応答しませんでした。backend.log を確認してください" >&2
  exit 1
fi

echo "画面を起動します。表示された http://localhost:5173 を開いてください"
cd frontend
if [ ! -d node_modules ]; then
  npm ci
fi
npm run generate:api
npx vite
