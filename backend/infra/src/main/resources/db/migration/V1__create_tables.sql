-- 最初の表を作る（Flyway が、ツールを初めて起動したときに1回だけ実行する）。
--
-- Flyway のきまり:
--   - ファイル名の「V1__」が版の番号。表の形を変えるときは、このファイルは直さずに V2__xxx.sql を足す
--     （配信者さんのPCには、もう V1 で作った表があるため。Flyway は、まだ実行していない版だけを順に実行する）
--   - 実行した版は flyway_schema_history という表に記録される
--
-- SQLite のきまり（公式: https://www.sqlite.org/datatype3.html ）:
--   - 型は TEXT（文字）、INTEGER（整数）、REAL（小数）などを使う。真偽（true/false）は INTEGER の 0 と 1 で表す
--   - 日時は、文字の順に並べると日時の順になる形（例: 2026-10-01T10:00:00.000000000Z）の TEXT で入れる
--     （infra/sqlite/SqliteValues.java を参照）

-- 応募
CREATE TABLE applications (
    id                TEXT    NOT NULL PRIMARY KEY,
    -- フォームの回答ID。UNIQUE で、同じ回答を2件登録できないようにする（同じ回答がほぼ同時に2回届いたときの最後の砦）
    submission_id     TEXT    NOT NULL UNIQUE,
    x_id              TEXT    NOT NULL,
    admiral_name      TEXT    NOT NULL,
    anonymous         INTEGER NOT NULL,
    simulator_url     TEXT    NOT NULL,
    form_version      TEXT    NOT NULL,
    -- フォームの回答（項目コード → 回答）。版ごとに項目が変わるので、JSON の文字で1つの列に入れる
    answers           TEXT    NOT NULL,
    received_at       TEXT    NOT NULL,
    -- 印（重複・再応募・条件外）の一覧。JSON
    flags             TEXT    NOT NULL,
    status            TEXT    NOT NULL,
    skip_reason       TEXT,
    stream_date       TEXT,
    memo              TEXT,
    analysis_memo     TEXT,
    archive_url       TEXT,
    -- 並び順キー（小さいほど先）
    position          INTEGER NOT NULL,
    won_lottery       INTEGER NOT NULL,
    updated_at        TEXT    NOT NULL,
    status_changed_at TEXT    NOT NULL,
    -- 版（同時変更の検知）。保存のたびに1増える
    version           INTEGER NOT NULL
);

-- 索引: 同じ応募者（XのID）の応募をすばやく探すため
CREATE INDEX applications_x_id ON applications (x_id);

-- 応募の変更履歴（ステータスやXのIDを、いつ・何から何へ変えたか）
CREATE TABLE application_history (
    -- 書き込んだ順の番号。SQLite が1から順に自動でつける（AUTOINCREMENT）。同じ時刻の変更も、変えた順に並べられる
    seq            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    id             TEXT    NOT NULL UNIQUE,
    application_id TEXT    NOT NULL,
    at             TEXT    NOT NULL,
    kind           TEXT    NOT NULL,
    from_value     TEXT    NOT NULL,
    to_value       TEXT    NOT NULL,
    note           TEXT
);

CREATE INDEX application_history_application_id ON application_history (application_id);

-- 抽選記録
CREATE TABLE lotteries (
    id          TEXT    NOT NULL PRIMARY KEY,
    mode        TEXT    NOT NULL,
    executed_at TEXT    NOT NULL,
    -- 乱数の種（64ビットの整数）。同じ種と対象者なら同じ結果になる
    seed        INTEGER NOT NULL,
    -- 対象者ごとの当たりやすさと結果の一覧。JSON
    entries     TEXT    NOT NULL
);

-- 設定（種類ごとに1行）。今は抽選設定（kind = 'lottery'）だけ
CREATE TABLE settings (
    kind    TEXT    NOT NULL PRIMARY KEY,
    -- 設定の中身。JSON
    value   TEXT    NOT NULL,
    version INTEGER NOT NULL
);
