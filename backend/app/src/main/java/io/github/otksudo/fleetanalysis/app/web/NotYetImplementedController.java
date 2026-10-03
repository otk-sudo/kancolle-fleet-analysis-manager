package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.ImportsApi;
import io.github.otksudo.fleetanalysis.app.api.model.ImportCsv200Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * まだ作っていないAPI（CSV取り込み）。
 *
 * <p>生成されたインターフェースはすべてのメソッドの実装を求める（未実装だとコンパイルエラーになる設定にしている）ので、
 * 「まだ作っていない」ことをはっきり返す実装を置いておく。
 */
@RestController
public class NotYetImplementedController implements ImportsApi {

    // TODO(段階9): 旧スプレッドシートのCSV取り込みを作る
    @Override
    public ResponseEntity<ImportCsv200Response> importCsv(String file, String mapping, String formVersion) {
        throw new NotImplementedYetException("CSV取り込みは段階9で作ります");
    }
}
