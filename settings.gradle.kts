// Gradleの設定ファイル（最初に読まれる）。
// このリポジトリは「マルチプロジェクト」構成で、役割ごとに小さなプロジェクト（モジュール）に分けている。
// include に書いたディレクトリが、それぞれ1つのプロジェクトとしてビルドされる。
// 詳しくは docs/guide/はじめに.md を参照。
rootProject.name = "kancolle-fleet-analysis-manager"

include(
    "backend:domain", // 業務ロジック（重複判定・抽選など）。他の技術に依存しない
    "backend:infra", // データの保存（SQLite とメモリ）
    "backend:app", // Spring Bootアプリ本体。APIの入口
)
