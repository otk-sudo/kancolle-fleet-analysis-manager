package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.LotteriesApi;
import io.github.otksudo.fleetanalysis.app.api.model.ListLotteries200Response;
import io.github.otksudo.fleetanalysis.app.api.model.Lottery;
import io.github.otksudo.fleetanalysis.app.api.model.LotteryRequest;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryMode;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 抽選の実行と記録のAPI（仕様 6章）。 */
@RestController
public class LotteriesController implements LotteriesApi {

    private final LotteryService lotteryService;

    public LotteriesController(LotteryService lotteryService) {
        this.lotteryService = lotteryService;
    }

    @Override
    public ResponseEntity<ListLotteries200Response> listLotteries(String cursor, Integer limit) {
        // 試作では抽選記録は少ないので、ページ分けせず新しい順に limit 件まで返す
        List<Lottery> items = new ArrayList<>();
        for (LotteryRecord record : lotteryService.history()) {
            if (items.size() >= limit) {
                break;
            }
            items.add(ApiMapper.toApi(record));
        }
        return ResponseEntity.ok(new ListLotteries200Response(items, null));
    }

    @Override
    public ResponseEntity<Lottery> runLottery(LotteryRequest request) {
        LotteryMode mode = LotteryMode.fromCode(request.getMode().getValue());
        int winners = 1;
        if (mode == LotteryMode.BULK) {
            if (request.getWinners() == null) {
                throw new InvalidValueException("まとめ抽選では当選人数（winners）を指定してください");
            }
            winners = request.getWinners();
        }
        // includeFlagged は省略できる項目。JSONで null が送られても動くようにする
        Set<String> includeFlagged = new HashSet<>();
        if (request.getIncludeFlagged() != null) {
            includeFlagged.addAll(request.getIncludeFlagged());
        }
        LotteryRecord record = lotteryService.run(mode, winners, includeFlagged, Actors.current());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiMapper.toApi(record));
    }
}
