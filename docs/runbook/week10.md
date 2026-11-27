# 결제 결과 미확인 주문 조사와 복구

이 절차는 로컬 PG fixture와 합성 주문을 사용하는 실습용이다. 결제 요청 뒤 주문이 확인 중으로 남아 있고 health는 정상일 때, 기존 거래를 확인해 같은 주문을 복구한다. 실험에 사용한 주문과 PG 계정, 관찰 시각을 기록한다.

## 1. 증상 확인과 대상 고정

1. 새로운 실험 주문 입력을 멈추고 baseline 주문 10건과 장애 주문 10건의 실제 ID 목록을 각각 보관한다. 재고의 시작 수량과 주문별 품목·수량도 기록한다.
2. 실제 관리 포트의 `/actuator/health`와 `/actuator/prometheus` 원문을 수집한다. `commerce_payment_unknown`은 DB에서 현재 `UNKNOWN`인 결제 시도 수다. 발송 직후 응답을 기다리는 시도도 포함하며 `PENDING`은 포함하지 않는다. 이 구현은 메트릭을 읽을 때마다 DB를 조회한다.
관리 포트가 `8081`인 로컬 실행 예시는 다음과 같다. 포트가 다르면 실제 포트를 사용한다.

```bash
curl -sS -i http://localhost:8081/actuator/health
curl -sS -i http://localhost:8081/actuator/prometheus
```

같은 시각의 주문·결제·재고 예약 상태를 읽고, `UNKNOWN` 주문 ID와 결제 시도 키, PG 주문 번호, 금액, 상태 시각을 기록한다. 아래 `?`에는 조사할 실제 주문 ID를 바인딩한다.

```sql
SELECT o.id AS order_id, o.status AS order_status, o.confirmed_at,
       p.attempt_key, p.provider_order_id, p.amount, p.status AS payment_status,
       p.transaction_key, p.dispatched_at, p.updated_at,
       r.status AS reservation_status
FROM orders o
JOIN payment_attempt p ON p.order_id = o.id
LEFT JOIN stock_reservation r ON r.order_id = o.id
WHERE o.id = ?;
```

health와 지표 응답이 예상과 다르면 실제 출력과 수집 시각을 보존하고 설정과 의존성을 확인한다. 지표는 개별 주문을 식별하지 않으므로 주문 목록은 DB 조회 결과로 확인한다.

## 2. 조치 전 진단

각 주문에 대해 결제 생성 요청의 adapter 기록과 PG의 읽기 전용 거래 조회 결과를 대조한다. PG 조회에는 실험에 사용한 동일한 계정과 저장된 `provider_order_id`를 사용한다. 주문 번호로 조회한 거래의 개수, 거래 키, 금액, 최종 상태를 기록한다. 테스트 fixture의 생성 호출 수도 기록한다. 카드 값이나 비밀값은 증거에 저장하지 않는다.

| 가설 | 확인할 관찰 |
| --- | --- |
| PG 처리가 아직 진행 중 | 해당 거래의 권위 조회 결과가 `PENDING` |
| PG 성공 뒤 생성 응답 유실 | 동일 주문·금액의 거래가 `SUCCESS`, 내부 상태가 `UNKNOWN`, 생성 요청 경계에 timeout 등 응답 유실 기록 |
| 성공 결과의 내부 적용 실패 | PG `SUCCESS`, 내부 적용 시도와 트랜잭션 실패 또는 롤백 기록 |

PG `SUCCESS`와 내부 `UNKNOWN`만으로 마지막 두 가설을 구분할 수 없다. 경계 기록이 부족하면 원인을 미확정으로 기록한다. 생성 요청에서 예외가 발생하면 adapter는 PG 주문 번호와 원인 예외 유형을 기록한다. 결제 쪽의 미확인 로그에서 같은 PG 주문 번호로 내부 주문 ID와 시도 키를 찾을 수 있다. 내부 반영 시작은 `STARTED`, 트랜잭션 완료는 `COMMITTED`, 실패 예외는 `FAILED`로 기록한다. `FAILED`가 보이면 결제 상태·예약·Outbox를 읽어 실제 반영 여부를 확인한다.

로컬 PG fixture의 주문별 거래 조회 예시는 다음과 같다. `PG_BASE_URL`, `PG_USER_ID`, `PROVIDER_ORDER_ID`는 실험에 사용한 값이다. 조회 응답과 시각을 보존한다.

```bash
curl -sS -i -G "$PG_BASE_URL/api/v1/payments" \
  -H "X-USER-ID: $PG_USER_ID" \
  --data-urlencode "orderId=$PROVIDER_ORDER_ID"
```

실험의 fault 단계에서는 장애 주문 10건 중 내부 성공 7건, `UNKNOWN` 3건, PG `SUCCESS` 10건과 각 거래의 일치 여부를 기록한다. 이 시점의 DB 조회, PG 거래, 로그, health와 Prometheus 원문을 별도 위치에 보존한 다음 복구한다. `reconcile`은 내부 상태를 변경하므로 증거 보존 전에 호출하지 않는다.

## 3. 복구

1. 장애 주문 중 `UNKNOWN` 세 건의 목록을 고정한다. 응답 유실 제어를 해제하고, 대상 callback은 첫 reconcile까지 보내지 않는다. 첫 복구 뒤에는 동일 알림을 보내 중복 반영 여부를 확인한다.
2. 동일 계정·주문·금액의 PG 거래가 한 건이며 `SUCCESS`인 경우, 각 주문 소유자의 식별자로 기존 API를 호출한다. 아래 주소와 값은 로컬 실행 환경과 해당 합성 주문에 맞춘다.

```bash
curl -sS -i -X POST "http://localhost:8080/api/v1/orders/<orderId>/payment/reconcile" \
  -H 'X-USER-ID: <주문 소유자의 loginId>'
```

3. 응답과 호출 시각을 기록하고 DB를 새로 조회한다. API는 종결 상태에 200, 미종결 상태에 202를 반환한다. HTTP 상태와 응답의 `paymentStatus`, `orderStatus`, `transactionKey`를 함께 확인한다.
4. PG 조회 실패, 거래 0건·복수 건, 주문이나 금액 불일치, 계속 `PENDING`인 경우 해당 주문의 복구를 중단하고 마지막 응답과 관찰 시각을 보존한다. 원인이 해결된 뒤 기존 거래를 다시 조회하고 같은 절차에 재진입한다.

이 API는 본인 주문만 처리한다. 주문 소유자의 식별자를 확인할 수 없는 경우 호출을 중단한다. PG create를 다시 호출하거나 DB 상태를 직접 변경해 복구하지 않는다.

## 4. 복구 확인과 재진입

복구 전 고정한 장애 주문 10건을 그대로 다시 조회한다. 내부 결제 성공 10건, `UNKNOWN` 0건, 같은 PG 거래 10건과 복구 중 create 호출 증가 0을 확인한다. `UNKNOWN` 동안 예약은 `RESERVED`이고 성공 뒤 `CONSUMED`인지, 성공 적용 시 재고가 추가 차감되지 않았는지 비교한다. 포인트와 쿠폰도 복구 전후 값을 비교한다.

```sql
SELECT event_id, aggregate_id, status, attempt_count, published_at, last_error
FROM outbox
WHERE event_id = ?;

SELECT order_id, product_id, quantity, confirmed_at
FROM purchase_activity
WHERE order_id = ?
ORDER BY product_id;
```

각 주문의 `order-confirmed:v1:<orderId>` Outbox 사실과 품목별 구매 활동을 확인한다. Outbox 발행 재시도 횟수와 구매 활동 수는 각각 기록한다. 반복 `reconcile`이나 callback 뒤에도 주문 확정, 예약 소비, Outbox와 구매 활동이 중복되지 않는지 확인한다.

구매 활동 저장이 끝나면 같은 DB에서 `WEEKLY_RANKING_JOB`을 실행한다. 대상 주차의 `periodStart`와 이전에 사용하지 않은 `revision`을 지정한다. 배치가 `COMPLETED`이면 `/api/v1/rankings/weekly?periodStart=<UTC 기준 월요일>`의 응답에서 실제 `periodStart`, 상품, 점수와 순위를 확인한다. 같은 주의 baseline과 다른 구매 활동도 기대 점수에 포함한다. 주문의 기존 `confirmed_at`을 기준으로 주간 소속을 판단한다. 공개된 snapshot을 직접 수정하지 않는다. [주간 랭킹 배치 로컬 실행](../week9/weekly-ranking-verification.md#로컬-실행)

결과가 일치하지 않으면 주문 ID, 거래 키, Outbox 상태, 구매 활동, snapshot과 관찰 시각을 보존하고 불일치 지점부터 조사한다. 다시 실행하기 전에는 거래를 새로 생성하지 않고 기존 주문과 거래의 현재 상태를 재조회한다. 같은 실행의 fault 원문을 덮어쓰지 않는다.

## 5. 실습 종료 기록

실행별로 baseline·fault·recovery의 ID 목록과 시각, PG 거래와 create 호출 수, 내부 상태, 예약·재고, 실제 health·Prometheus 응답, 경계 로그, 구매 활동과 랭킹 결과를 보관한다. 가설별 예상·실제·판정과 중간 실패를 함께 기록한다. 테스트와 관련 회귀의 실제 실행 결과, 실행하지 못한 항목을 구분한다.

로컬 재현에는 Docker가 필요하다. 테스트가 MySQL·Redis를 띄우고 health가 UP인 것을 확인한 뒤 사고를 시작한다. 복구 뒤에는 Kafka와 후속 처리 앱을 띄운다. 실행을 마치면 테스트가 시작한 자원을 정리한다.

```bash
./gradlew :apps:commerce-api:test --tests '*CheckoutIncidentTest' --rerun
```

`apps/commerce-api/build/reports/week10/run-*/`에 실행별 증거가 남는다.

| 파일 | 확인할 내용 |
| --- | --- |
| `baseline.json`, `fault.json`, `recovery.json` | 주문 ID, 결제·예약·PG 거래 상태, 생성 호출 수, 재고와 포인트·쿠폰 |
| `*-health.json`, `*-prometheus.txt`, `*-payment.log` | 단계별 실제 관리 포트 응답과 주문에 연결되는 결제 로그 |
| `diagnosis.json`, `reconcile.json` | 가설별 관찰과 판정, 같은 세 주문의 복구 결과 |
| `delivery.json`, `ranking.json` | Outbox 발행과 구매 활동, 주간 snapshot 입력, 랭킹 API 결과 |
| `result.json` 또는 `failure.json` | 복구 중 PG create 증가 수와 fault 증거 SHA-256, 또는 실패 단계와 시각 |

기준선·장애·복구 단계의 `UNKNOWN` 지표 기대값은 각각 0, 3, 0이다. 실습 DB에 정상·장애 주문에서 산 상품 한 개씩만 집계되면 해당 상품의 점수는 20, 순위는 1이다.

## 6. 회귀 검증 범위

| 기능 | 확인 항목과 대표 테스트 |
| --- | --- |
| 상품·브랜드·좋아요·관리자 | 공개 조회와 삭제, 접근 권한, 과거 주문 보존, 캐시 보호. 상품·브랜드·좋아요와 관리자 API E2E 테스트 |
| 포인트·주문·PG | 포인트 원장과 재고 예약, 중복 결제 방지, 불확실한 거래의 처리 보류, 주문 소유권. `PointV1ApiE2ETest`, `OrderV1ApiE2ETest`, `PaymentIntegrationTest`, `PaymentProviderContractTest` |
| Outbox·구매 활동 | 발행 누락 복구, relay 재발행과 소비 중복 방지, 실제 구매 활동 적재. `OrderBackfillRecoveryTest`, `OutboxDeliveryE2ETest`, `CheckoutIncidentTest` |
| 쿠폰 | 총량, 동일 사용자 중복 발급, 동시 실행 제한, 본인 목록. `CouponApiIntegrationTest`, `CouponSoldOutCacheApiIntegrationTest`, `CouponQueryServiceIntegrationTest` |
| 주간 랭킹 | 사고 주문의 실제 구매 활동 반영, 동일 인스턴스 재시작, 늦은 구매 리비전, 공개 API. `CheckoutIncidentTest`, `WeeklyRankingJobTest`, `WeeklyRankingApiE2ETest` |

실습은 로컬 합성 계정의 생성 응답 유실을 검증한다. 운영 PG·실계정·부하 조건과 내부 DB 반영 실패를 별도 사고로 주입하는 실험은 이 검증 범위 밖이다.

2026-11-27 로컬 재현에서 세 단계 모두 health가 `UP`이었고, `UNKNOWN` 지표는 0 → 3 → 0으로 바뀌었다. 장애 주문 세 건의 요청은 PG 응답 제한 1초를 넘겼다. PG 거래를 유지한 채 세 주문을 복구했고, create 추가 호출은 0건이었다. 두 주문 집합에서 구매 활동 20건이 저장됐고 주간 랭킹 API는 점수 20, 순위 1을 반환했다. 복구를 마친 뒤 fault 증거 파일 5개의 SHA-256이 그대로인지 확인했다.

- API 전체 첫 실행은 679건 중 678건이 통과했다. 사고 테스트 한 건은 실험 전 Redis 연결 초기화 제한을 초과했다. 전체 재실행에서는 JDK가 응답 제한 초과를 두 예외 형태로 전달하는 것이 드러나, 최종적으로 요청 경과 시간과 경계 로그를 함께 확인하도록 보강했다.
- 테스트 검토 후 `CheckoutIncidentTest`, `PaymentIntegrationTest`, `PaymentProviderContractTest`, `ArchitectureTest` 23건을 재실행해 모두 통과했다. API 전체 검사는 이 변경 뒤에는 실행하지 않았다.
- relay 64건, streamer 80건, batch 44건과 각 모듈의 `check`가 통과했다. 배치의 조건부 성능 실험 3건은 건너뛰었다. 저장소 전체 `ktlintCheck`도 통과했다.
