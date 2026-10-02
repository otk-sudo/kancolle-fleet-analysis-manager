package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.IntakeApi;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC（Webの仕組み）の設定。
 * {@code WebMvcConfigurer} を実装すると、Spring が用意した設定に自分の設定を足せる。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FormKeyInterceptor formKeyInterceptor;

    public WebConfig(FormKeyInterceptor formKeyInterceptor) {
        this.formKeyInterceptor = formKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 応募受付APIにだけ、秘密キーの確認を付ける。URLは生成コードの定数を使い、定義とずれないようにする
        registry.addInterceptor(formKeyInterceptor).addPathPatterns(IntakeApi.PATH_SUBMIT_APPLICATION);
    }
}
