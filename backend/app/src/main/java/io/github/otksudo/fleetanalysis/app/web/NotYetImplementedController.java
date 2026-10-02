package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.ImportsApi;
import io.github.otksudo.fleetanalysis.app.api.UsersApi;
import io.github.otksudo.fleetanalysis.app.api.model.CreateUserRequest;
import io.github.otksudo.fleetanalysis.app.api.model.ImportCsv200Response;
import io.github.otksudo.fleetanalysis.app.api.model.User;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 試作ではまだ作らないAPI（ユーザー管理、CSV取り込み）。
 *
 * <p>生成されたインターフェースはすべてのメソッドの実装を求める（未実装だとコンパイルエラーになる設定にしている）ので、
 * 「まだ作っていない」ことをはっきり返す実装を置いておく。
 */
@RestController
public class NotYetImplementedController implements UsersApi, ImportsApi {

    // TODO(段階3): Cognitoと連携してユーザー管理を作る
    @Override
    public ResponseEntity<List<User>> listUsers() {
        throw new NotImplementedYetException("ユーザー管理は段階3で作ります");
    }

    @Override
    public ResponseEntity<User> createUser(CreateUserRequest createUserRequest) {
        throw new NotImplementedYetException("ユーザー管理は段階3で作ります");
    }

    @Override
    public ResponseEntity<Void> deleteUser(String userId) {
        throw new NotImplementedYetException("ユーザー管理は段階3で作ります");
    }

    // TODO(段階7): 旧スプレッドシートのCSV取り込みを作る
    @Override
    public ResponseEntity<ImportCsv200Response> importCsv(String file, String mapping, String formVersion) {
        throw new NotImplementedYetException("CSV取り込みは段階7で作ります");
    }
}
