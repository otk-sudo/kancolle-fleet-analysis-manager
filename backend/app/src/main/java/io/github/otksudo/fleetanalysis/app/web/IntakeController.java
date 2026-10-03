package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.IntakeApi;
import io.github.otksudo.fleetanalysis.app.api.model.IntakeRequest;
import io.github.otksudo.fleetanalysis.app.api.model.IntakeResponse;
import io.github.otksudo.fleetanalysis.domain.application.Application;
import io.github.otksudo.fleetanalysis.domain.application.ApplicationService;
import io.github.otksudo.fleetanalysis.domain.application.IntakeCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Googleフォーム（Apps Script）からの応募を受け付けるAPI（仕様 3章）。
 * フォーム用の秘密キーの確認は {@link FormKeyFilter} が先に行う。
 */
@RestController
public class IntakeController implements IntakeApi {

    private final ApplicationService applicationService;

    public IntakeController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Override
    public ResponseEntity<IntakeResponse> submitApplication(IntakeRequest request) {
        Application application = applicationService.submit(new IntakeCommand(
                request.getSubmissionId(),
                request.getSubmittedAt().toInstant(),
                request.getFormVersion(),
                request.getAnswers()));
        IntakeResponse response = new IntakeResponse(application.id(), ApiMapper.toApiFlags(application.flags()));
        // 201 Created: 新しく登録した、という意味のHTTPステータス
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
