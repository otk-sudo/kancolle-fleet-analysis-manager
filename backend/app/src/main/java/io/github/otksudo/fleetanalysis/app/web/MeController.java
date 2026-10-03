package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.MeApi;
import io.github.otksudo.fleetanalysis.app.api.model.Me;
import io.github.otksudo.fleetanalysis.app.api.model.Permission;
import io.github.otksudo.fleetanalysis.app.api.model.Role;
import io.github.otksudo.fleetanalysis.app.security.CurrentUsers;
import io.github.otksudo.fleetanalysis.domain.auth.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログインしている人と、その人ができることを返すAPI。
 * 画面はこれを見て、できない操作のボタンを出さないようにする（最終的な確認はサーバーが各APIで行う）。
 */
@RestController
public class MeController implements MeApi {

    private final CurrentUsers users;

    public MeController(CurrentUsers users) {
        this.users = users;
    }

    @Override
    public ResponseEntity<Me> getMe() {
        CurrentUser user = users.current();
        List<Permission> permissions = users.permissions(user).stream()
                .map(permission -> Permission.fromValue(permission.code()))
                .toList();
        Me me = new Me(user.id(), user.displayName(), Role.fromValue(user.role().code()), user.mfaRequired(),
                users.isStreamOperator(user), permissions);
        return ResponseEntity.ok(me);
    }
}
