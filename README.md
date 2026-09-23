# Hyperliquid Tax Tracker JP

日本在住ユーザー向けに、bitbankからHyperliquidまでの取引履歴を整理するMVPです。

## 開発環境

- Backend: Java 21+, Spring Boot 3.5.x, Maven
- Frontend: Next.js 15.x, TypeScript
- Database: PostgreSQL 16
- Local orchestration: Docker Compose

## 起動

Docker Composeを利用する場合:

```bash
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend health: http://localhost:8080/api/health
- PostgreSQL: localhost:5432

ローカルで個別に実行する場合は、先にPostgreSQLを起動してからBackendとFrontendを起動します。

```bash
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

`local-data/`は実データ確認専用で、Gitへ追加しません。

仕様は[`docs/requirements.md`](docs/requirements.md)、[`docs/tax-spec.md`](docs/tax-spec.md)、[`docs/data-source-spec.md`](docs/data-source-spec.md)、[`docs/implementation-spec.md`](docs/implementation-spec.md)を参照してください。
