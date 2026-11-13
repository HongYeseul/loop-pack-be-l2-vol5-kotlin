# 8주차 실행 준비

JDK 21·Docker를 사용한다. 저장소 루트에서 `make init`으로 pre-commit 훅을 설정한다. 기존 로컬 인프라의 MySQL은 13306, Redis primary는 6379, replica는 6380, Kafka는 19092다. Compose 기본 MySQL 포트는 유지하며 실행 시 MYSQL_PORT=13306을 지정한다.

API·commerce-streamer 실행, 운영 API의 닫기·재개, 캠페인·본인 결과 조회는 [로컬 실행 문서](coupon-local-operations.md)를 따른다. API에서 새 DB 접수 행을 만들지 않으며 Stream consumer는 commerce-streamer에서 명시적으로 활성화한다.

## 검사

```bash
./gradlew :modules:coupon:test :apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest'
./gradlew ktlintCheck :modules:coupon:check :apps:commerce-api:check :apps:commerce-streamer:check
```
