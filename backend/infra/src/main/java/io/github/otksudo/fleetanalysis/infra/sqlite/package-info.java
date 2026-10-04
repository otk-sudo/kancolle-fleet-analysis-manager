/**
 * データを SQLite（1つのファイルのデータベース）に保存する実装（仕様 8章、docs/design.md 1章）。
 *
 * <p>配信者さんのPCでは、このデータベースのファイルにすべての応募・履歴・抽選記録・設定が入る。
 * 入口は {@link io.github.otksudo.fleetanalysis.infra.sqlite.SqliteStorage}。表の形は
 * {@code src/main/resources/db/migration} の SQL ファイルで決めている。
 */
package io.github.otksudo.fleetanalysis.infra.sqlite;
