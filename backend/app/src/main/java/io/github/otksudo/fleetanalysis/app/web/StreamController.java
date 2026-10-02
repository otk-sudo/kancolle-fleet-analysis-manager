package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.StreamApi;
import io.github.otksudo.fleetanalysis.app.api.model.StreamView;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 配信用画面に出す内容のAPI（仕様 7.2）。XのIDと課金額は含めない。 */
@RestController
public class StreamController implements StreamApi {

    private final ApplicationService applicationService;

    public StreamController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public ResponseEntity<StreamView> getStreamView() {
        // 「分析中」の人がいなければ current は null（画面には「準備中」などを出す）
        StreamView view = new StreamView(applicationService.streamView().map(ApiMapper::toApi).orElse(null));
        return ResponseEntity.ok(view);
    }
}
