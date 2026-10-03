# CLAUDE.md

このリポジトリで作業するときは、まず次を読むこと。
- 開発ルール: docs/development.md（特に「0. 初学者でもわかるように書く」は最優先）
- 仕様書: docs/spec.md
- 設計メモと開発の段階: docs/design.md

要点:
- 開発者は多くの技術が初めてなので、コード・コメント・ドキュメント・PRは初学者が理解できるように書く
- コメント、ドキュメント、コミット、PRは日本語。識別子は英語
- APIを変えるときは api/openapi.yaml を先に変える。生成コードは手で編集しない
- backend/domain は Spring・AWS に依存させない
- PRはCIが通ったらレビューエージェントでレビューし、指摘を直してから開発者の最終確認に回す
- 変更前に `./gradlew build` と `cd frontend && npm run lint && npm run build` を通す
