package io.github.otksudo.fleetanalysis.domain.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 重複・再応募の印の決め方（仕様 5.2）。
 *
 * <p>印は「同じ人（同じXのID）のほかの応募の状態」から決まる。なので、同じ人の応募のどれかが変わったら、
 * その人の応募すべてについて決め直す（付け直す）。印が古いまま残ると、抽選や「次の人へ」で飛ばされ続けるため。
 *
 * <p>決め方（自分より前に受け付けた応募だけを見る）:
 * <ul>
 *   <li>重複: 自分が「未着手・分析予定・分析中」で、自分より前に受け付けた「未着手・分析予定・分析中」の応募がある
 *   <li>再応募: 自分より前に受け付けた「分析済み・見送り・落選」の応募がある
 * </ul>
 * 例: 古い応募を見送りにすると、新しい応募の「重複」は外れて「再応募」になる。
 *
 * <p>条件外の印（段階7）は、ここでは決めないのでそのまま残す。
 */
public final class ApplicantFlags {

    /** 受付の早い順。受付日時が同じなら応募IDの順（いつも同じ順になるように） */
    static final Comparator<Application> RECEIVED_ORDER =
            Comparator.comparing(Application::receivedAt).thenComparing(Application::id);

    private ApplicantFlags() {
    }

    /**
     * {@code target} につけるべき印を決める。
     *
     * @param target        印を決める応募
     * @param sameApplicant 同じXのIDの応募（target を含んでいてもよい）
     */
    public static List<Flag> judge(Application target, Collection<Application> sameApplicant) {
        // 自分より前に受け付けた応募を、新しい順に並べる（一番近い前の応募を「関係する応募」にするため）
        List<Application> earlier = new ArrayList<>();
        for (Application other : sameApplicant) {
            if (!other.id().equals(target.id()) && RECEIVED_ORDER.compare(other, target) < 0) {
                earlier.add(other);
            }
        }
        earlier.sort(RECEIVED_ORDER.reversed());

        List<Flag> flags = new ArrayList<>();
        if (target.status().isOpen()) {
            for (Application other : earlier) {
                if (other.status().isOpen()) {
                    flags.add(new Flag(FlagType.DUPLICATE,
                            "同じXのIDの応募が「" + other.status().label() + "」で残っています", other.id()));
                    break;
                }
            }
        }
        for (Application other : earlier) {
            if (!other.status().isOpen()) {
                flags.add(new Flag(FlagType.REAPPLY, "過去に応募があります（" + other.status().label() + "）", other.id()));
                break;
            }
        }
        // 条件外など、ここで決めない印はそのまま残す
        for (Flag flag : target.flags()) {
            if (flag.type() != FlagType.DUPLICATE && flag.type() != FlagType.REAPPLY) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
