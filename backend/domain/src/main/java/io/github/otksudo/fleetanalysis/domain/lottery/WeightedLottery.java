package io.github.otksudo.fleetanalysis.domain.lottery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 当たりやすさ（重み）を考慮した抽選（仕様 6章）。
 *
 * <p>仕組み: 対象者の重みを横一列に並べた長さの棒を考え、その上にランダムに点を打つ。
 * 点が落ちた区間の人が当選する。重みが大きい人ほど区間が長いので当たりやすい。
 * 当選した人は棒から取り除き、当選人数に達するまで繰り返す（同じ人が二度当たらない「非復元抽出」）。
 *
 * <p>乱数の「種（seed）」と対象者が同じなら、何度実行しても同じ結果になる。
 * 抽選記録に種を残しておけば、後から結果を再現して公平性を確認できる（仕様 6.4）。
 * 対象者を渡す順番が変わっても結果が変わらないよう、内部で応募ID順に並べ替えてから抽選する
 * （データベースから読み直したときに、保存したときと同じ順で返ってくるとは限らないため）。
 */
public final class WeightedLottery {

    // インスタンスを作らせないためのprivateコンストラクタ（staticメソッドだけを持つクラスの定番の書き方）
    private WeightedLottery() {
    }

    /**
     * 抽選を行う。
     *
     * @param entries 対象者（順番は問わない）。同じ応募IDを2回含めてはいけない
     * @param winners 当選人数。対象者より多い場合は全員当選
     * @param seed    乱数の種
     * @return 当選者の応募ID（当選した順）
     * @throws IllegalArgumentException 当選人数が負、または同じ応募IDが重複している場合
     */
    public static List<String> draw(List<LotteryEntry> entries, int winners, long seed) {
        if (winners < 0) {
            throw new IllegalArgumentException("winners must not be negative");
        }
        // java.util.Random は近い種どうしで最初の乱数が似通う性質があり、種を連番にすると偏る。
        // そのため、種をよく混ぜてから使う SplittableRandom を使う。
        SplittableRandom random = new SplittableRandom(seed);

        // 同じ人が2回入っていると、二度当たって当選枠を余分に使ってしまうため、先に確かめる
        Set<String> seen = new HashSet<>();
        for (LotteryEntry entry : entries) {
            if (!seen.add(entry.applicationId())) {
                throw new IllegalArgumentException("duplicate applicationId: " + entry.applicationId());
            }
        }

        // まだ当選していない人。渡された順番に左右されないよう、応募ID順に並べる
        List<LotteryEntry> remaining = new ArrayList<>(entries);
        remaining.sort(Comparator.comparing(LotteryEntry::applicationId));
        List<String> result = new ArrayList<>();

        while (result.size() < winners && !remaining.isEmpty()) {
            // 棒の全長（残っている人の重みの合計）
            double total = 0;
            for (LotteryEntry entry : remaining) {
                total += entry.weight();
            }

            // 0以上 total 未満のどこかに点を打つ
            double point = random.nextDouble() * total;

            // 先頭から区間の長さを引いていき、0を下回ったところの人が当選
            int picked = remaining.size() - 1; // 計算誤差で最後まで下回らなかった場合は最後の人
            for (int i = 0; i < remaining.size(); i++) {
                point -= remaining.get(i).weight();
                if (point < 0) {
                    picked = i;
                    break;
                }
            }
            result.add(remaining.remove(picked).applicationId());
        }
        return result;
    }
}
