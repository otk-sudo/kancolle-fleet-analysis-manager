package io.github.otksudo.fleetanalysis.domain.auth;

import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 関係者への「配信の操作」の許可（仕様 2章）。許可するかは、配信者が関係者ごとに決める（初期値: 許可しない）。
 */
public class StreamOperatorService {

    private final StreamOperatorRepository repository;
    private final UserDirectory directory;

    public StreamOperatorService(StreamOperatorRepository repository, UserDirectory directory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** 関係者1人分の許可の状態 */
    public record Operator(String userId, String displayName, boolean allowed) {
    }

    /** 名簿にいる関係者全員と、それぞれ許可しているか */
    public List<Operator> list() {
        Set<String> allowed = repository.findAll();
        List<Operator> result = new ArrayList<>();
        for (UserDirectory.Entry entry : directory.findAll()) {
            if (entry.role() == Role.STAFF) {
                result.add(new Operator(entry.id(), entry.displayName(), allowed.contains(entry.id())));
            }
        }
        return result;
    }

    /** このユーザーが配信の操作を許可されているか（関係者以外は、ここでは常に false） */
    public boolean isOperator(String userId) {
        return repository.isOperator(userId);
    }

    /** 関係者に配信の操作を許可する。関係者でない人は、許可の対象にならないので断る */
    public void grant(String userId, String grantedBy) {
        UserDirectory.Entry entry = directory.find(userId)
                .orElseThrow(() -> new NotFoundException("ユーザーが見つかりません: " + userId));
        if (entry.role() != Role.STAFF) {
            // 配信者・運営は、許可しなくても配信の操作ができる
            throw new InvalidValueException("配信の操作を許可できるのは関係者だけです（" + entry.displayName() + " さんは" + entry.role().label() + "）");
        }
        repository.grant(userId, grantedBy);
    }

    /** 許可を取り消す。名簿から消えた人の許可も取り消せるよう、名簿は確かめない */
    public void revoke(String userId) {
        repository.revoke(userId);
    }
}
