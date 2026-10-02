@echo off
chcp 65001 >nul
rem ↑ 1行目: 実行するコマンドを画面に表示しない。2行目: 文字コードをUTF-8にして、日本語の表示が文字化けしないようにする
rem   （この2行より前に日本語を書くと文字化けで誤動作することがあるので、必ず先頭に置く）
rem
rem 試作版をまとめて起動する Windows 用のスクリプト（scripts/dev.sh の Windows 版）。
rem   1. 前回のバックエンドが残っていたら止める
rem   2. バックエンド（Spring Boot、ポート8080）を、サンプルデータ入り（demo プロファイル）で起動する
rem   3. バックエンドが応答するまで待つ
rem   4. 画面（Vite の開発サーバー、ポート5173）を起動する
rem 止めるときは、このウィンドウを閉じる。
rem   Ctrl+C では画面だけが止まり、バックエンドが裏で残ることがある（裏で動かしたものには Ctrl+C が届かないため）。
rem   残っても、次にこのスクリプトを起動したときに自動で止めてから起動し直す。
rem
rem 使い方: エクスプローラーで scripts フォルダの dev.cmd をダブルクリックする
rem         （またはコマンドプロンプトで、リポジトリの一番上のフォルダから scripts\dev.cmd と入力する）

rem setlocal: このスクリプトの中で変えた設定（変数など）を、終わったら元に戻す
setlocal

rem このスクリプトがある場所の1つ上（リポジトリの一番上）へ移動する。どこから実行しても同じ動きにするため
rem   %~dp0 は「このスクリプトがあるフォルダ」の意味。/d はドライブが違っても移動するための指定
cd /d "%~dp0.."

rem 必要なソフトが入っているか確かめる。where は「そのコマンドがどこにあるか」を探すコマンド
rem   Java は、環境変数 JAVA_HOME が正しく設定されていれば gradlew.bat がそちらを使うので、それでもよい
if exist "%JAVA_HOME%\bin\java.exe" goto :check_node
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
rem curl.exe は Windows 10（バージョン1803以降）と Windows 11 に最初から入っている
where curl.exe >nul 2>nul
if errorlevel 1 (
  echo curl.exe が見つかりません。Windows 10 の古い版では入っていないので、Windows Update をしてください
  goto :error
)

rem 前回のバックエンドが残っていたら止める。古いコードのまま動き続けたり、ポート8080が使えなかったりするため
rem   gradlew.bat --stop は、裏で動いている Gradle（とその中で動くバックエンド）を止めるコマンド
curl.exe -fs -o nul http://localhost:8080/applications
if not errorlevel 1 (
  echo 前回のバックエンドが残っていたので止めます...
  call gradlew.bat --stop >nul 2>nul
  timeout /t 3 /nobreak >nul
)

echo バックエンドを起動しています（初回は部品のダウンロードで数分かかることがあります）...
if exist backend.log del backend.log 2>nul
if exist backend.exited del backend.exited 2>nul
rem start /b: 同じウィンドウの裏側で動かす（ウィンドウを閉じれば一緒に止まる）。出力は backend.log に書く
rem   --args=--spring.profiles.active=demo: サンプルデータを入れる設定（demo プロファイル）で起動する
rem   「& echo exited> backend.exited」: バックエンドが終わったら（失敗して終わったときも）、目印のファイルを作る。
rem   待っている間にこのファイルができたら、起動に失敗したとわかる
start "" /b cmd /c "call gradlew.bat :backend:app:bootRun --args=--spring.profiles.active=demo --console=plain > backend.log 2>&1 & echo exited> backend.exited"

rem バックエンドが応答するまで、1秒ごとに確かめる（最大10分 = 600回）
set /a tries=0
:wait
curl.exe -fs -o nul http://localhost:8080/applications
if not errorlevel 1 goto :started
if exist backend.exited (
  echo バックエンドの起動に失敗しました。backend.log を確認してください
  echo よくある原因: Java 21 ではない Java が使われている、ポート8080をほかのソフトが使っている
  goto :error
)
set /a tries+=1
if %tries% geq 600 (
  echo 10分待ってもバックエンドが応答しませんでした。backend.log を確認してください
  echo 初回でダウンロードに時間がかかっているだけなら、もう一度実行すると続きから進みます
  goto :error
)
rem timeout /t 1: 1秒待つ。/nobreak はキーを押しても待つのをやめない指定
timeout /t 1 /nobreak >nul
goto :wait

:started
echo バックエンドが起動しました。ログは backend.log にあります
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
rem 画面が止まったら（Ctrl+C のあと「バッチ ジョブを終了しますか」に N と答えたとき）、バックエンドも止める
cd ..
call gradlew.bat --stop >nul 2>nul
goto :eof

:error
rem 途中まで起動したバックエンドを止める
call gradlew.bat --stop >nul 2>nul
rem ダブルクリックで開いたときに、エラーを読む前にウィンドウが閉じないように止める
pause
exit /b 1
