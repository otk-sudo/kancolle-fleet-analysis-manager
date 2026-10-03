package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.ImportsApi;
import io.github.otksudo.fleetanalysis.app.api.UsersApi;
import io.github.otksudo.fleetanalysis.app.api.model.CreateUserRequest;
import io.github.otksudo.fleetanalysis.app.api.model.ImportCsv200Response;
import io.github.otksudo.fleetanalysis.app.api.model.User;
import java.util.List;
import org.springframework.http.ResponseEntity;
import io.github.otksudo.fleetanalysis.app.security.CurrentUsers;
import io.github.otksudo.fleetanalysis.domain.auth.Permission;
import org.springframework.web.bind.annotation.RestController;

/**
 * 試作ではまだ作らないAPI（ユーザー管理、CSV取り込み）。
 *
 * <p>生成されたインターフェースはすべてのメソッドの実装を求める（未実装だとコンパイルエラーになる設定にしている）ので、
 * 「まだ作っていない」ことをはっきり返す実装を置いておく。
 */
@RestController
public class NotYetImplementedController implements UsersApi, ImportsApi {

    private final CurrentUsers users;

    public NotYetImplementedController(CurrentUsers users) {
        this.users = users;
    }

    // TODO(段階7): Cognito と連携してユーザー管理を作る（docs/design.md 5章の段階7）。
    // 権限の確認は先に入れておき、権限のない人には「未実装」より先に403を返す
    @Override
    public ResponseEntity<List<User>> listUsers() {
        users.require(Permission.MANAGE_USERS);
        throw new NotImplementedYetException("ユーザー管理は段階7で作ります");
    }

    @Override
    public ResponseEntity<User> createUser(CreateUserRequest createUserRequest) {
        users.require(Permission.MANAGE_USERS);
        throw new NotImplementedYetException("ユーザー管理は段階7で作ります");
    }

    @Override
    public ResponseEntity<Void> deleteUser(String userId) {
        users.require(Permission.MANAGE_USERS);
        throw new NotImplementedYetException("ユーザー管理は段階7で作ります");
    }

    // TODO(段階7): 旧スプレッドシートのCSV取り込みを作る
    @Override
    public ResponseEntity<ImportCsv200Response> importCsv(String file, String mapping, String formVersion) {
        users.require(Permission.IMPORT_CSV);
        throw new NotImplementedYetException("CSV取り込みは段階7で作ります");
    }
}
