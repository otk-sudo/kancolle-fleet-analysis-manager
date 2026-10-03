package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.SettingsApi;
import io.github.otksudo.fleetanalysis.app.api.model.Settings;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRepository;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryService;
import io.github.otksudo.fleetanalysis.domain.lottery.LotterySettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 設定の取得・変更のAPI（仕様 10章）。
 * 試作では「抽選設定（lottery）」だけに対応し、ほかの種類は 501（未実装）を返す。
 */
@RestController
public class SettingsController implements SettingsApi {

    private static final String LOTTERY = "lottery";

    private final LotteryService lotteryService;

    public SettingsController(LotteryService lotteryService) {
        this.lotteryService = lotteryService;
    }

    @Override
    public ResponseEntity<Settings> getSettings(String kind) {
        requireLottery(kind);
        return ResponseEntity.ok(toApi(lotteryService.settings()));
    }

    @Override
    public ResponseEntity<Settings> putSettings(String kind, Settings settings) {
        requireLottery(kind);
        Map<String, Object> value = settings.getValue();
        LotterySettings newSettings = new LotterySettings(
                readBoolean(value, "enabled"),
                readBoolean(value, "lossBonusEnabled"),
                readNumber(value, "lossBonusStrength"));
        // 画面が読み込んだときの版（version）を渡し、その間にほかの人が変えていたら 409 にする
        return ResponseEntity.ok(toApi(lotteryService.updateSettings(newSettings, settings.getVersion())));
    }

    private static void requireLottery(String kind) {
        if (!LOTTERY.equals(kind)) {
            // TODO(段階9): 選択肢（options）・ステータス（statuses）・条件ルール（rules）の設定に対応する
            throw new NotImplementedYetException("試作では抽選設定（lottery）だけに対応しています: " + kind);
        }
    }

    private static Settings toApi(LotteryRepository.VersionedSettings versioned) {
        LotterySettings settings = versioned.settings();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", settings.enabled());
        value.put("lossBonusEnabled", settings.lossBonusEnabled());
        value.put("lossBonusStrength", settings.lossBonusStrength());
        return new Settings(Settings.KindEnum.LOTTERY, versioned.version(), value);
    }

    // JSONの値は Map<String, Object> で届くので、型を確かめてから取り出す
    private static boolean readBoolean(Map<String, Object> value, String key) {
        if (value.get(key) instanceof Boolean b) {
            return b;
        }
        throw new InvalidValueException(key + " は true か false で指定してください");
    }

    private static double readNumber(Map<String, Object> value, String key) {
        if (value.get(key) instanceof Number n) {
            return n.doubleValue();
        }
        throw new InvalidValueException(key + " は数値で指定してください");
    }
}
