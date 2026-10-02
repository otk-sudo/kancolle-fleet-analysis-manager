@echo off
chcp 65001 >nul
rem ↑ 1行目: 実行するコマンドを画面に表示しない。2行目: 文字コードをUTF-8にして、日本語の表示が文字化けしないようにする
rem   （この2行より前に日本語を書くと文字化けで誤動作することがあるので、必ず先頭に置く）
rem
rem 試作版をまとめて起動する Windows 用のスクリプト（scripts/dev.sh の Windows 版）。
rem   1. バックエンド（Spring Boot、ポート8080）を、サンプルデータ入り（demo プロファイル）で起動する
rem   2. バックエンドが応答するまで待つ
rem   3. 画面（Vite の開発サーバー、ポート5173）を起動する
rem 止めるときは、このウィンドウを閉じる（または Ctrl+C を押し、「バッチ ジョブを終了しますか」に Y と答える）。
rem 画面と一緒にバックエンドも止まる。
rem
rem 使い方: エクスプローラーで scripts フォルダの dev.cmd をダブルクリックする
rem         （またはコマンドプロンプトで、リポジトリの一番上のフォルダから scripts\dev.cmd と入力する）

rem setlocal: このスクリプトの中で変えた設定（環境変数など）を、終わったら元に戻す
setlocal

rem このスクリプトがある場所の1つ上（リポジトリの一番上）へ移動する。どこから実行しても同じ動きにするため
rem   %~dp0 は「このスクリプトがあるフォルダ」の意味。/d はドライブが違っても移動するための指定
cd /d "%~dp0.."

rem 必要なソフトが入っているか確かめる。where は「そのコマンドがどこにあるか」を探すコマンド
rem   Java は、環境変数 JAVA_HOME が設定されていれば gradlew.bat がそちらを使うので、それでもよい
if defined JAVA_HOME goto :check_node
where java >nul 2>nul
if errorlevel 1 (
  echo Java が見つかりません。Java 21 をインストールしてから、もう一度実行してください
  goto :error
)
:check_node
where npm >nul 2>nul
if errorlevel 1 (
  echo Node.js が見つかりません。Node.js 22 をインストールしてから、もう一度実行してください
  goto :error
)

rem すでにバックエンドが動いていれば、2つ目は起動しない（ポート8080が使えず失敗するため）
curl.exe -fs -o nul http://localhost:8080/applications
if not errorlevel 1 (
  echo バックエンドはすでに起動しています。そのまま画面を起動します
  goto :frontend
)

echo バックエンドを起動しています（初回は依存ライブラリのダウンロードで数分かかることがあります）...
rem SPRING_PROFILES_ACTIVE=demo: サンプルデータを入れる設定（demo プロファイル）で起動する
set SPRING_PROFILES_ACTIVE=demo
if exist backend.log del backend.log
rem start /b: 同じウィンドウの裏側で動かす（ウィンドウを閉じれば一緒に止まる）。出力は backend.log に書く
start "" /b cmd /c "gradlew.bat :backend:app:bootRun --console=plain > backend.log 2>&1"

rem バックエンドが応答するまで、1秒ごとに確かめる（最大5分 = 300回）
set /a tries=0
:wait
curl.exe -fs -o nul http://localhost:8080/applications
if not errorlevel 1 goto :started
rem ビルドや起動の失敗が backend.log に出ていたら、待たずに止める
if exist backend.log (
  findstr /c:"BUILD FAILED" /c:"APPLICATION FAILED TO START" backend.log >nul
  if not errorlevel 1 (
    echo バックエンドの起動に失敗しました。backend.log を確認してください
    goto :error
  )
)
set /a tries+=1
if %tries% geq 300 (
  echo 5分待ってもバックエンドが応答しませんでした。backend.log を確認してください
  goto :error
)
rem timeout /t 1: 1秒待つ。/nobreak はキーを押しても待つのをやめない指定
timeout /t 1 /nobreak >nul
goto :wait

:started
echo バックエンドが起動しました。ログは backend.log にあります

:frontend
echo 画面を起動します。表示された http://localhost:5173 をブラウザで開いてください
cd frontend
rem npm や npx は中身が別のスクリプトなので、call をつけて呼ぶ（つけないと、そこでこのスクリプトが終わってしまう）
if not exist node_modules (
  call npm ci
  if errorlevel 1 goto :error
)
call npm run generate:api
if errorlevel 1 goto :error
call npx vite
goto :eof

:error
rem ダブルクリックで開いたときに、エラーを読む前にウィンドウが閉じないように止める
pause
exit /b 1
