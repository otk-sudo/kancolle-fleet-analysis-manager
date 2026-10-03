package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.StreamApi;
import io.github.otksudo.fleetanalysis.app.api.model.StreamView;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配信用画面に出す内容と、配信の操作のAPI（仕様 7.2、7.3）。XのIDと課金額は含めない。
 */
@RestController
public class StreamController implements StreamApi {

    private final ApplicationService applicationService;
    private final LotteryService lotteryService;

    public StreamController(ApplicationService applicationService, LotteryService lotteryService) {
        this.applicationService = applicationService;
        this.lotteryService = lotteryService;
    }

    @Override
    public ResponseEntity<StreamView> getStreamView() {
        // 「分析中」の人がいなければ current は null（画面には「準備中」などを出す）
        StreamView view = new StreamView(applicationService.streamView().map(ApiMapper::toApi).orElse(null));
        return ResponseEntity.ok(view);
    }

    @Override
    public ResponseEntity<StreamView> advanceStream() {
        // 抽選を使う設定のときは、未着手の人は抽選で選ぶので「次の人へ」では選ばない
        boolean includePending = !lotteryService.settings().settings().enabled();
        StreamView view = new StreamView(applicationService.advanceStream(includePending)
                .map(ApiMapper::toApi).orElse(null));
        return ResponseEntity.ok(view);
    }
}
