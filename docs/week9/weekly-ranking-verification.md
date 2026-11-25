# 9주차 주간 구매 랭킹 검증

## 검증 실행

저장소 루트에서 JDK 21과 Docker를 사용한다. 테스트는 Testcontainers로 격리한 DB를 사용한다.

```bash
./gradlew :apps:commerce-batch:test --tests '*WeeklyRanking*'
./gradlew :apps:commerce-api:test --tests '*WeeklyRankingApiE2ETest'
./gradlew :apps:commerce-batch:check :apps:commerce-api:check
```

2026-11-19에 세 테이블 구조를 적용한 작업 트리에서 두 모듈의 `check`가 통과했다. 테스트 결과 XML 기준 Batch 39건, API 677건을 실행했고 실패와 건너뛴 테스트는 0건이었다. 이 중 주간 랭킹 관련 테스트는 36건이다. API의 ArchUnit과 두 모듈의 ktlint도 통과했다.

| 검사 | 확인 내용 |
| --- | --- |
| [WeeklyRankingJobTest](../../apps/commerce-batch/src/test/kotlin/com/loopers/job/ranking/WeeklyRankingJobTest.kt) | 주간 시작 포함·끝 제외, 체크포인트 재시작, 늦은 주문의 새 리비전 반영, 공개 실패 롤백과 재실행, 입력 정리 |
| [WeeklyRankingRetryTest](../../apps/commerce-batch/src/test/kotlin/com/loopers/job/ranking/step/score/WeeklyRankingRetryTest.kt) | 일시적인 처리·쓰기 실패의 재시도, 영구 오류 시 실패 |
| [WeeklyRankingSchedulerTest](../../apps/commerce-batch/src/test/kotlin/com/loopers/job/ranking/WeeklyRankingSchedulerTest.kt) | UTC 기준 직전 주 선택, 연도 경계, 스케줄러 프로필 |
| [WeeklyRankingApiE2ETest](../../apps/commerce-api/src/test/kotlin/com/loopers/ranking/interfaces/api/WeeklyRankingApiE2ETest.kt) | 순위가 확정된 최신 리비전, 처리 중 행 제외, 직전 주 조회, 입력 검증과 개수 제한 |

## 실패와 재시작 관찰

2026-11-19에 별도의 MySQL 8.0 컨테이너에서 동일한 배치 JAR를 네 차례 실행했다. 청크 크기는 2, 집계 구간은 `[2026-08-17T00:00:00Z, 2026-08-24T00:00:00Z)`였다. 주문 42의 두 품목이 각각 스냅샷에 들어가도록 구성했다.

| 주문 ID | 상품 ID | 수량 | 주문 확정 시각 UTC |
| ---: | ---: | ---: | --- |
| 42 | 7 | 2 | `2026-08-17T01:00:00Z` |
| 42 | 8 | 1 | `2026-08-17T01:00:00Z` |
| 43 | 7 | 3 | `2026-08-18T01:00:00Z` |
| 44 | 9 | 5 | `2026-08-19T01:00:00Z` |

| 실행 | JobInstance ID | JobExecution ID | 점수 StepExecution ID | 결과 |
| --- | ---: | ---: | ---: | --- |
| `r1` 정상 실행 | 1 | 1 | 2 | `COMPLETED`. 비교 기준으로 입력 4행과 순위 3행을 추출했다. |
| `r2` 첫 실행 | 2 | 2 | 5 | `FAILED`. 상품 7·8의 점수가 커밋된 뒤 상품 9의 쓰기를 DB 제약으로 실패시켰다. |
| `r2` 재시작 | 2 | 3 | 6 | `COMPLETED`. 입력 고정 Step을 건너뛰었고 상품 9 한 건을 읽어 썼다. 공개 StepExecution 7에서 순위 3행을 확정했다. |
| `r3` 새 집계 | 3 | 4 | 9 | `COMPLETED`. 같은 주의 늦은 구매를 포함해 입력 5행을 고정했다. |

`r2` 첫 실행을 마친 직후 DB에는 상품 7의 5점과 상품 8의 1점이 있었고 두 행의 `rank_position`은 `NULL`이었다. 점수 Step의 `READ_COUNT`는 3, `WRITE_COUNT`는 2, `COMMIT_COUNT`는 1이었다. 이때 DB에 저장된 실행 컨텍스트에서 `WEEKLY_RANKING_PRODUCT_READER.read.count=2`를 확인했다.

`r2`가 멈춘 동안 주문 45의 상품 9 구매 2개를 추가했다. 확정 시각은 `2026-08-20T01:00:00Z`다. 같은 리비전을 재시작했을 때 상품 9는 5점으로 유지됐고, 원래 주차의 새 리비전 `r3`에서 7점이 됐다.

| 결과 | 1위 | 2위 | 3위 |
| --- | --- | --- | --- |
| `r1` 정상 실행, `r2` 재시작 | 상품 7 · 5점 | 상품 9 · 5점 | 상품 8 · 1점 |
| `r3` 재계산 | 상품 9 · 7점 | 상품 7 · 5점 | 상품 8 · 1점 |

이 실험에서는 배치 실행과 DB 행을 확인했다. 계산 중인 리비전의 제외와 최신 순위 조회는 `WeeklyRankingApiE2ETest`에서 HTTP 응답으로 검증했다.

### 입력과 결과 체크섬

각 실행 직후 실제 DB 행을 도구로 추출해 SHA-256을 계산했다. 입력은 `order_id, product_id` 순으로 정렬한 `order_id, product_id, quantity, confirmed_at`이고, 결과는 순위순으로 정렬한 `product_id, score, rank_position`이다. 필드는 탭, 행은 LF로 구분하고 마지막 행에도 LF를 넣었다. 확정 시각은 UTC 소수점 이하 6자리로 출력했다.

| 대상 | `r1` 정상 실행과 `r2` 재시작에서 확인한 SHA-256 |
| --- | --- |
| 입력 4행 | `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298` |
| 결과 3행 | `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2` |

`r1`과 `r2`는 체크섬과 비교에 사용한 행이 모두 같았다. `r3`에서는 입력·결과 체크섬이 모두 바뀌었다. 체크섬은 재현 기록에 남겼으며, 앱은 이를 업무 테이블에 저장하지 않는다.

## 로컬 실행

1. 기존 로컬 환경에 `purchase_activity`가 준비돼 있어야 한다. 랭킹 테이블을 처음 만드는 DB에는 [랭킹 스키마](../../apps/commerce-batch/src/main/resources/db/weekly-ranking.sql)를 적용한다. 두 메타데이터 테이블이 이미 있는 DB에는 배치를 중지한 상태에서 [전환 SQL](../../apps/commerce-batch/src/main/resources/db/weekly-ranking-remove-metadata.sql)을 적용한다. 전환 SQL은 기존 입력·결과 행이 들어 있는 임시 MySQL에서 확인했다.
2. `MYSQL_PORT`를 로컬 DB 포트에 맞춘다. 아래는 Batch 메타데이터가 이미 있는 DB에서 집계하거나 실패한 작업을 다시 시작하는 실행 예시다. 새 DB에서 최초 실행할 때는 `initialize-schema=always`를 사용한다.

   ```bash
   MYSQL_PORT=13306 ./gradlew :apps:commerce-batch:bootRun --args='--spring.profiles.active=local --spring.batch.job.name=WEEKLY_RANKING_JOB --spring.batch.jdbc.initialize-schema=never periodStart=2026-08-17,java.time.LocalDate revision=1,java.lang.Long'
   ```

3. 실패한 작업을 이어갈 때는 같은 DB와 식별 파라미터를 사용한다. 늦은 구매를 새 입력으로 반영할 때는 원래 주차의 리비전을 올린다. API가 실행 중이면 `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`으로 결과를 조회한다.

실습 예시와 같은 실패를 재현하려면 격리 DB에 위 네 구매 행을 넣고 `--ranking.chunk-size=2`로 실행한다. `r2` 실행 전에 해당 리비전의 상품 9만 막는 제약을 추가한다. 실패 직후 두 점수 행과 체크포인트를 확인하고, 제약을 제거한 뒤 같은 파라미터로 실행한다.

```sql
ALTER TABLE weekly_ranking_result
    ADD CONSTRAINT reject_product_9
    CHECK (snapshot_id <> 'weekly-2026-08-17-r2' OR product_id <> 9);

-- r2 실패와 저장된 점수 두 행 확인 후 실행
ALTER TABLE weekly_ranking_result DROP CHECK reject_product_9;
```

## 제출 전 확인할 항목

| 과제가 요구하는 항목 | 현재 확인 범위 |
| --- | --- |
| 주문 확정부터 Outbox·Kafka·구매 활동·배치·API까지 하나의 연결 테스트 | 기존 주문 발행·소비 재현 테스트와 주간 배치·API 테스트가 각각 있다. 하나의 연결 테스트에서 동일 이벤트를 재발행하고 랭킹 점수까지 비교하는 검증은 남아 있다. |
| API가 미공개와 구매 0건으로 완료된 주차를 구분 | 현재 API는 두 경우 모두 조회할 순위가 없는 것으로 취급해 직전 주차를 조회한다. 응답에 상태, `periodEnd`, `snapshotId`, 입력 고정 시점이 없다. |
| 삭제·비노출 상품을 제외한 뒤 `limit` 적용 | 현재 API는 결과 테이블을 읽으면서 상품 공개 조건이나 현재 표시 정보를 연결하지 않는다. |
| 입력 고정 manifest의 상태·시점·건수·체크섬 | 현재 스키마의 입력 행과 Batch 실행 상태로 재시작을 관리한다. 체크섬은 위 재현에서 계산했고 스냅샷 단위 manifest는 저장하지 않는다. |
| 공개 커밋 이후 Batch 메타데이터 기록 실패 재현 | 이미 공개된 스냅샷을 다시 처리하는 테스트와 공개 트랜잭션 롤백 테스트는 있다. 공개 커밋 직후 메타데이터 쓰기만 실패시키는 실험은 남아 있다. |
