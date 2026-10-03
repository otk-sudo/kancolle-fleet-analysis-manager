package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.model.Application;
import io.github.otksudo.fleetanalysis.app.api.model.Flag;
import io.github.otksudo.fleetanalysis.app.api.model.FlagType;
import io.github.otksudo.fleetanalysis.app.api.model.Lottery;
import io.github.otksudo.fleetanalysis.app.api.model.LotteryEntriesInner;
import io.github.otksudo.fleetanalysis.app.api.model.StreamApplicant;
import io.github.otksudo.fleetanalysis.domain.lottery.LotteryRecord;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * domain のクラスと、API用に自動生成されたクラス（app.api.model）を変換する。
 *
 * <p>同じ「応募」でも、業務ロジック用（domain）とAPIのやりとり用（生成コード）でクラスを分けている。
 * こうしておくと、APIの形を変えても業務ロジックに影響せず、逆も同じになる。変換はこのクラスに集める。
 */
final class ApiMapper {

    /** 日時は日本時間（+09:00）でAPIに出す。配信者さんが見る時刻をそろえるため */
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private ApiMapper() {
    }

    static Application toApi(io.github.otksudo.fleetanalysis.domain.application.Application domain) {
        Application api = new Application();
        api.setId(domain.id());
        api.setxId(domain.xId().value());
        api.setAdmiralName(domain.admiralName());
        api.setAnonymous(domain.anonymous());
        api.setSimulatorUrl(URI.create(domain.simulatorUrl()));
        api.setStatus(domain.status().code());
        api.setStreamDate(domain.streamDate());
        api.setMemo(domain.memo());
        api.setFlags(toApiFlags(domain.flags()));
        api.setFormVersion(domain.formVersion());
        api.setAnswers(new LinkedHashMap<>(domain.answers()));
        api.setReceivedAt(toApi(domain.receivedAt()));
        api.setUpdatedAt(toApi(domain.updatedAt()));
        return api;
    }

    static List<Application> toApi(List<io.github.otksudo.fleetanalysis.domain.application.Application> domains) {
        List<Application> result = new ArrayList<>();
        for (var domain : domains) {
            result.add(toApi(domain));
        }
        return result;
    }

    static List<Flag> toApiFlags(List<io.github.otksudo.fleetanalysis.domain.application.Flag> flags) {
        List<Flag> result = new ArrayList<>();
        for (var flag : flags) {
            Flag api = new Flag(FlagType.fromValue(flag.type().code()), flag.reason());
            api.setRelatedApplicationId(flag.relatedApplicationId());
            result.add(api);
        }
        return result;
    }

    /** 配信用画面の内容。XのIDと課金額は domain 側ですでに除かれている（仕様 7.2） */
    static StreamApplicant toApi(io.github.otksudo.fleetanalysis.domain.application.StreamView view) {
        StreamApplicant api = new StreamApplicant();
        api.setDisplayName(view.displayName());
        api.setSimulatorUrl(URI.create(view.simulatorUrl()));
        api.setAnswers(view.answers());
        api.setPrevious(view.previous());
        return api;
    }

    static Lottery toApi(LotteryRecord record) {
        List<LotteryEntriesInner> entries = new ArrayList<>();
        for (LotteryRecord.Entry entry : record.entries()) {
            entries.add(new LotteryEntriesInner(entry.applicationId(), BigDecimal.valueOf(entry.weight()), entry.won()));
        }
        return new Lottery(
                record.id(),
                Lottery.ModeEnum.fromValue(record.mode().code()),
                toApi(record.executedAt()),
                record.executedBy(),
                // 種は64ビットの整数。JavaScriptの数値では桁が欠けるため、文字列で渡す
                Long.toString(record.seed()),
                entries);
    }

    static OffsetDateTime toApi(Instant instant) {
        return OffsetDateTime.ofInstant(instant, JAPAN);
    }
}
