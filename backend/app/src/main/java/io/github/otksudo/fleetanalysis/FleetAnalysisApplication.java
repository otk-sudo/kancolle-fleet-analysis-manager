package io.github.otksudo.fleetanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * アプリの起点（mainメソッドを持つクラス）。
 *
 * <p>{@code @SpringBootApplication} は Spring Boot アプリであることを示す目印（アノテーション）。これ1つで次の3つが有効になる。
 * <ul>
 *   <li>このパッケージ以下にある {@code @RestController} などのクラスを探して、部品（Bean）として自動で登録する
 *   <li>依存ライブラリに応じて、Webサーバーなどの設定を自動で行う（オートコンフィグ）
 *   <li>このクラス自身を設定クラスとして扱う
 * </ul>
 * 部品を探す範囲は「このクラスのパッケージとその下」。app・infra・domain のどのプロジェクトの部品も見つけられるよう、
 * このクラスは一番上のパッケージ（io.github.otksudo.fleetanalysis）に置いている。
 */
@SpringBootApplication
public class FleetAnalysisApplication {

    public static void main(String[] args) {
        // Springの仕組みを起動し、Webサーバー（標準ではポート8080）を立ち上げる
        SpringApplication.run(FleetAnalysisApplication.class, args);
    }
}
