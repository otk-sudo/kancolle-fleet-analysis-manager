package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.DevLoginApi;
import io.github.otksudo.fleetanalysis.app.api.model.DevLoginRequest;
import io.github.otksudo.fleetanalysis.app.api.model.DevLoginResponse;
import io.github.otksudo.fleetanalysis.app.api.model.DevUser;
import io.github.otksudo.fleetanalysis.app.api.model.Role;
import io.github.otksudo.fleetanalysis.app.security.DevLogin;
import io.github.otksudo.fleetanalysis.app.security.DevUsers;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手元で動かすときだけの仮ログインのAPI。
 *
 * <p>{@code @ConditionalOnProperty} で、設定 {@code app.auth.mode} が {@code dev} のときだけ作る。
 * 本番（cognito）ではこのクラス自体がないので、/dev/login を呼んでも404になる。
 */
@RestController
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "dev")
public class DevLoginController implements DevLoginApi {

    private final DevLogin devLogin;
    private final DevUsers devUsers;

    public DevLoginController(DevLogin devLogin, DevUsers devUsers) {
        this.devLogin = devLogin;
        this.devUsers = devUsers;
    }

    @Override
    public ResponseEntity<List<DevUser>> listDevUsers() {
        return ResponseEntity.ok(devUsers.users().stream()
                .map(user -> new DevUser(user.id(), user.displayName(), Role.fromValue(user.role().code()),
                        user.mfaConfigured()))
                .toList());
    }

    @Override
    public ResponseEntity<DevLoginResponse> devLogin(DevLoginRequest request) {
        DevUsers.DevUser user = devUsers.user(request.getUserId())
                .orElseThrow(() -> new NotFoundException("試し用のユーザーが見つかりません: " + request.getUserId()));
        return ResponseEntity.ok(new DevLoginResponse(devLogin.issue(user)));
    }
}
