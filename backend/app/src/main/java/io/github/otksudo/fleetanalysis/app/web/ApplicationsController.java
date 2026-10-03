package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.ApplicantsApi;
import io.github.otksudo.fleetanalysis.app.api.ApplicationsApi;
import io.github.otksudo.fleetanalysis.app.api.model.Application;
import io.github.otksudo.fleetanalysis.app.api.model.ApplicationPage;
import io.github.otksudo.fleetanalysis.app.api.model.ApplicationUpdate;
import io.github.otksudo.fleetanalysis.app.api.model.BulkStatusRequest;
import io.github.otksudo.fleetanalysis.app.api.model.BulkStatusResponse;
import io.github.otksudo.fleetanalysis.app.api.model.FlagType;
import io.github.otksudo.fleetanalysis.app.api.model.HistoryEntry;
import io.github.otksudo.fleetanalysis.app.api.model.KeepRequest;
import io.github.otksudo.fleetanalysis.app.api.model.MoveApplicationRequest;
import io.github.otksudo.fleetanalysis.app.api.model.SkipReason;
import io.github.otksudo.fleetanalysis.app.api.model.VersionedId;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.XId;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationChanges;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationFilter;
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
            List<String> status,
            String purpose,
            String rankingEffort,
            String q,
            FlagType flag,
            String order,
            String cursor,
            Integer limit) {
        List<ApplicationStatus> statuses = new ArrayList<>();
        if (status != null) {
            for (String code : status) {
                statuses.add(ApplicationStatus.fromCode(code));
            }
        }
        io.github.otksudo.fleetanalysis.domain.application.FlagType domainFlag =
                flag == null ? null : io.github.otksudo.fleetanalysis.domain.application.FlagType.fromCode(flag.getValue());
        List<io.github.otksudo.fleetanalysis.domain.application.Application> all = applicationService.list(
                new ApplicationFilter(statuses, purpose, rankingEffort, domainFlag, q), order);

        // ページ分け。「何件目から」を cursor として渡すわかりやすい方法をとる。
        // 応募は数千件までの想定で、一覧は毎回全件を並べ替えてから切り出す（design.md 2章「規模の想定」）
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
        ApplicationChanges changes = ApplicationChanges.none()
                .withStatus(update.getStatus() == null ? null : ApplicationStatus.fromCode(update.getStatus()))
                .withSkipReason(toDomain(update.getSkipReason()))
                .withStreamDate(update.getStreamDate())
                .withMemo(update.getMemo())
                .withAnalysisMemo(update.getAnalysisMemo())
                .withArchiveUrl(update.getArchiveUrl())
                .withXId(update.getxId());
        // 「配信日を消す」は、null（変えない）と区別するため clearStreamDate で受け取る
        if (Boolean.TRUE.equals(update.getClearStreamDate())) {
            changes = changes.withClearStreamDate();
        }
        var updated = applicationService.update(applicationId, update.getVersion(), changes, Actors.current());
        return ResponseEntity.ok(ApiMapper.toApi(updated));
    }

    @Override
    public ResponseEntity<List<HistoryEntry>> listApplicationHistory(String applicationId) {
        return ResponseEntity.ok(ApiMapper.toApiHistory(applicationService.history(applicationId)));
    }

    @Override
    public ResponseEntity<Application> keepApplication(String applicationId, KeepRequest request) {
        var kept = applicationService.keep(applicationId, request.getVersion(), Actors.current());
        return ResponseEntity.ok(ApiMapper.toApi(kept));
    }

    @Override
    public ResponseEntity<BulkStatusResponse> bulkChangeStatus(BulkStatusRequest request) {
        List<ApplicationService.VersionedId> targets = new ArrayList<>();
        for (VersionedId item : request.getItems()) {
            targets.add(new ApplicationService.VersionedId(item.getId(), item.getVersion()));
        }
        var updated = applicationService.bulkChangeStatus(
                targets,
                ApplicationStatus.fromCode(request.getStatus()),
                toDomain(request.getSkipReason()),
                Actors.current());
        return ResponseEntity.ok(new BulkStatusResponse(ApiMapper.toApi(updated)));
    }

    @Override
    public ResponseEntity<Void> moveApplication(String applicationId, MoveApplicationRequest request) {
        applicationService.move(applicationId, request.getAfter());
        // 204 No Content: 成功したが返す中身はない、という意味のHTTPステータス
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<Application>> listApplicantHistory(String xId) {
        return ResponseEntity.ok(ApiMapper.toApi(applicationService.sameApplicant(XId.parse(xId))));
    }

    @Override
    public ResponseEntity<Void> deleteApplicant(String xId) {
        // TODO(段階3): ログインができたら、運営だけが実行できるように権限を確認する（仕様 2章）
        applicationService.deleteApplicant(XId.parse(xId));
        return ResponseEntity.noContent().build();
    }

    private static io.github.otksudo.fleetanalysis.domain.application.SkipReason toDomain(SkipReason reason) {
        return reason == null ? null : io.github.otksudo.fleetanalysis.domain.application.SkipReason.fromCode(reason.getValue());
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
