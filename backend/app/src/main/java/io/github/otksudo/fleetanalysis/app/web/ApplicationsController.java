package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.ApplicantsApi;
import io.github.otksudo.fleetanalysis.app.api.ApplicationsApi;
import io.github.otksudo.fleetanalysis.app.api.model.Application;
import io.github.otksudo.fleetanalysis.app.api.model.ApplicationPage;
import io.github.otksudo.fleetanalysis.app.api.model.ApplicationUpdate;
import io.github.otksudo.fleetanalysis.app.api.model.FlagType;
import io.github.otksudo.fleetanalysis.app.api.model.MoveApplicationRequest;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 応募の一覧・詳細・変更と、応募者ごとの履歴・削除のAPI（仕様 5章、8.1）。
 *
 * <p>{@code @RestController} は「HTTPリクエストを受け取ってJSONを返すクラス」という目印。
 * URLやパラメーターの受け取り方は、自動生成されたインターフェース（ApplicationsApi など）にすでに書いてあるので、
 * ここでは {@code implements} して中身だけを書く。
 */
@RestController
public class ApplicationsController implements ApplicationsApi, ApplicantsApi {

    private final ApplicationService applicationService;

    // コンストラクターの引数に書いた部品は、Spring が自動で渡してくれる（ServiceConfig で作ったもの）
    public ApplicationsController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public ResponseEntity<ApplicationPage> listApplications(
            List<String> status, String purpose, FlagType flag, String order, String cursor, Integer limit) {
        List<ApplicationStatus> statuses = new ArrayList<>();
        if (status != null) {
            for (String code : status) {
                statuses.add(ApplicationStatus.fromCode(code));
            }
        }
        io.github.otksudo.fleetanalysis.domain.application.FlagType domainFlag =
                flag == null ? null : io.github.otksudo.fleetanalysis.domain.application.FlagType.fromCode(flag.getValue());
        List<io.github.otksudo.fleetanalysis.domain.application.Application> all =
                applicationService.list(statuses, purpose, domainFlag, order);

        // ページ分け。試作では「何件目から」を cursor として渡すわかりやすい方法をとる
        // TODO(段階1): DynamoDB版では、DynamoDBが返す「続きの位置」を cursor にする
        int start = parseCursor(cursor);
        int end = Math.min(start + limit, all.size());
        List<Application> items = start < all.size() ? ApiMapper.toApi(all.subList(start, end)) : List.of();
        String nextCursor = end < all.size() ? Integer.toString(end) : null;
        return ResponseEntity.ok(new ApplicationPage(items, nextCursor));
    }

    @Override
    public ResponseEntity<Application> getApplication(String applicationId) {
        return ResponseEntity.ok(ApiMapper.toApi(applicationService.get(applicationId)));
    }

    @Override
    public ResponseEntity<Application> updateApplication(String applicationId, ApplicationUpdate update) {
        ApplicationStatus status = update.getStatus() == null ? null : ApplicationStatus.fromCode(update.getStatus());
        // TODO(段階2): 「配信日を消す」と「配信日を変えない」をJSONで区別できるようにする。
        // 今の生成コードではどちらも null になるため、試作では null を「変えない」として扱う
        var updated = applicationService.update(applicationId, status, update.getStreamDate(), false, update.getMemo());
        return ResponseEntity.ok(ApiMapper.toApi(updated));
    }

    @Override
    public ResponseEntity<Void> moveApplication(String applicationId, MoveApplicationRequest request) {
        applicationService.move(applicationId, request.getAfter());
        // 204 No Content: 成功したが返す中身はない、という意味のHTTPステータス
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<Application>> listApplicantHistory(String xId) {
        return ResponseEntity.ok(ApiMapper.toApi(applicationService.history(XId.parse(xId))));
    }

    @Override
    public ResponseEntity<Void> deleteApplicant(String xId) {
        // TODO(段階3): ログインができたら、運営だけが実行できるように権限を確認する（仕様 2章）
        applicationService.deleteApplicant(XId.parse(xId));
        return ResponseEntity.noContent().build();
    }

    private static int parseCursor(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            int value = Integer.parseInt(cursor);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // 下の例外にまとめる
        }
        throw new InvalidValueException("cursor の値が正しくありません");
    }
}
