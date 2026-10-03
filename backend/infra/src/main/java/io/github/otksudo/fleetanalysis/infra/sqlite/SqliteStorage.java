package io.github.otksudo.fleetanalysis.infra.sqlite;

import io.github.otksudo.fleetanalysis.domain.application.ApplicationRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite のデータベースのファイルを開き、応募と抽選の保存先（リポジトリ）を作る入口。
 *
 * <p>開くときにすること:
 * <ol>
 *   <li>ファイルを置くフォルダがなければ作る（ファイル自体は、SQLite が初めてつないだときに作る）
 *   <li>Flyway で表を作る・新しい形に変える（{@code db/migration} の SQL ファイルのうち、まだ実行していないものだけ）
 * </ol>
 */
public final class SqliteStorage {

    /**
     * ほかの書き込みが終わるのを待つ時間（ミリ秒）。
     * SQLite は同時に1つしか書き込めないので、ほかの書き込み中なら、この時間まで待ってから書く
     * （公式: https://www.sqlite.org/c3ref/busy_timeout.html ）
     */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final ApplicationRepository applications;
    private final LotteryRepository lotteries;

    private SqliteStorage(DataSource dataSource) {
        // JdbcClient: SQL を書いて、値を ? の代わりに名前（:id など）で渡せる Spring の道具。
        // 値を SQL の文字に直接つなげないので、SQL インジェクション（値に SQL を紛れ込ませる攻撃）を防げる
        JdbcClient jdbc = JdbcClient.create(dataSource);
        // TransactionTemplate: 中で行った書き込みを「全部するか、何もしないか」にまとめる道具（トランザクション）
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.applications = new SqliteApplicationRepository(jdbc, transaction);
        this.lotteries = new SqliteLotteryRepository(jdbc, transaction);
    }

    /** データベースのファイルを開く（なければ作る）。表も、なければ作る。 */
    public static SqliteStorage open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("データベースのフォルダを作れませんでした: " + file, e);
        }
        DataSource dataSource = dataSource(file);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new SqliteStorage(dataSource);
    }

    /**
     * データベースへのつなぎ方（DataSource）を作る。
     *
     * <p>TransactionMode.IMMEDIATE: トランザクションを始めた時点で「これから書く」と SQLite に伝え、ほかの書き込みを待たせる。
     * 読んでから書く途中でほかの書き込みとぶつかって失敗するのを防ぐ（公式: https://www.sqlite.org/lang_transaction.html ）
     */
    private static DataSource dataSource(Path file) {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return dataSource;
    }

    public ApplicationRepository applications() {
        return applications;
    }

    public LotteryRepository lotteries() {
        return lotteries;
    }
}
