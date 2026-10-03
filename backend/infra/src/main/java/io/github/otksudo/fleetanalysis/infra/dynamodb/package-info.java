/**
 * DynamoDB に保存する実装（本番と、手元で DynamoDB Local を使うとき用）。
 *
 * <p>テーブルは1つだけ（テーブル名は設定で決める。本番は {@code Main}）で、応募・抽選記録・設定などを
 * キー（PK・SK）の付け方で区別して入れる「単一テーブル設計」にしている。詳しくは docs/design.md の2章。
 *
 * <p>AWS SDK のクラス（DynamoDbClient など）は、このパッケージの外には出さない。
 * app からは {@link io.github.otksudo.fleetanalysis.infra.dynamodb.DynamoDbStorage} だけを使い、
 * 受け取るのは domain のインターフェース（ApplicationRepository など）にしている。
 */
package io.github.otksudo.fleetanalysis.infra.dynamodb;
