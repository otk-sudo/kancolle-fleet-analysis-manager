package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.StreamApi;
import io.github.otksudo.fleetanalysis.app.api.model.StreamOperator;
import io.github.otksudo.fleetanalysis.app.api.model.StreamView;
import io.github.otksudo.fleetanalysis.app.security.CurrentUsers;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.auth.CurrentUser;
import io.github.otksudo.fleetanalysis.domain.auth.Permission;
import io.github.otksudo.fleetanalysis.domain.auth.StreamOperatorService;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配信用画面に出す内容と、配信の操作のAPI（仕様 7.2、7.3）。XのIDと課金額は含めない。
 * 関係者への配信の操作の許可（仕様 2章）もここで扱う。
 */
@RestController
public class StreamController implements StreamApi {

    private final ApplicationService applicationService;
    private final LotteryService lotteryService;
    private final StreamOperatorService streamOperators;
    private final CurrentUsers users;

    public StreamController(ApplicationService applicationService, LotteryService lotteryService,
            StreamOperatorService streamOperators, CurrentUsers users) {
        this.applicationService = applicationService;
        this.lotteryService = lotteryService;
        this.streamOperators = streamOperators;
        this.users = users;
    }

    @Override
    public ResponseEntity<StreamView> getStreamView() {
        // TODO(段階5): ログインしなくても、配信用の閲覧専用URLの鍵があれば見られるようにする（仕様 7.2）
        users.require(Permission.VIEW);
        // 「分析中」の人がいなければ current は null（画面には「準備中」などを出す）
        StreamView view = new StreamView(applicationService.streamView().map(ApiMapper::toApi).orElse(null));
        return ResponseEntity.ok(view);
    }

    @Override
    public ResponseEntity<StreamView> advanceStream() {
        // 「次の人へ」は配信の操作。配信者・運営と、許可された関係者ができる
        CurrentUser user = users.require(Permission.STREAM_OPERATION);
        // 抽選を使う設定のときは、未着手の人は抽選で選ぶので「次の人へ」では選ばない
        boolean includePending = !lotteryService.settings().settings().enabled();
        StreamView view = new StreamView(applicationService.advanceStream(includePending, user.displayName())
                .map(ApiMapper::toApi).orElse(null));
        return ResponseEntity.ok(view);
    }

    @Override
    public ResponseEntity<List<StreamOperator>> listStreamOperators() {
        users.require(Permission.MANAGE_STREAM_OPERATORS);
        return ResponseEntity.ok(streamOperators.list().stream()
                .map(operator -> new StreamOperator(operator.userId(), operator.displayName(), operator.allowed()))
                .toList());
    }

    @Override
    public ResponseEntity<Void> grantStreamOperator(String userId) {
        CurrentUser user = users.require(Permission.MANAGE_STREAM_OPERATORS);
        streamOperators.grant(userId, user.displayName());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> revokeStreamOperator(String userId) {
        users.require(Permission.MANAGE_STREAM_OPERATORS);
        streamOperators.revoke(userId);
        return ResponseEntity.noContent().build();
    }
}
