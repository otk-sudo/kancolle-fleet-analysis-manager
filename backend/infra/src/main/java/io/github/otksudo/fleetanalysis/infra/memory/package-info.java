/**
 * メモリ上に保存する実装（テスト・開発用）。
 *
 * <p>アプリを止めるとデータは消える。テストや、サンプルデータで画面を試すとき（scripts/dev.sh）に使う。
 * 配信者さんのPCでは、同じインターフェースを実装した SQLite の実装（infra/sqlite）を使う。
 */
package io.github.otksudo.fleetanalysis.infra.memory;
