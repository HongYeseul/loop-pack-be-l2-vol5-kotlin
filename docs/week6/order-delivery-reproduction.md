# 주문 발행 경계 재현

현재 주문 API의 포인트 결제 경로를 사용한다. PG 결제와 재고 예약은 이 실험에 포함하지 않는다.

## 실행

```bash
./gradlew :apps:commerce-api:test --tests '*OrderDeliveryReproductionTest'
```

두 테스트가 격리된 MySQL·Redis·Kafka에서 각각 실제 주문 생성·확정 API를 호출한다. 각 사례는 별도 사용자·상품·consumer group을 사용하며, 상대 사례의 실행 결과를 참조하지 않는다. 별도 JVM에서 실행한 실제 commerce-streamer가 같은 MySQL과 Kafka를 사용한다. API 테스트 설정과 streamer 설정이 서로의 컴포넌트나 스키마 생성을 바꾸지 않도록 프로세스를 분리한다.

- 원본 토픽: 매 실행마다 생성하는 `order-delivery-<UUID>`, 파티션 2개, 복제 수 1
- consumer group: 테스트 메서드마다 생성하는 `purchase-delivery-<UUID>`. 시작 전에 현재 토픽 끝을 시작 offset으로 지정해 앞선 사례의 record를 소비하지 않음
- 주문 시각: `2026-10-30T01:02:03.123456789Z` 고정. 최초 확정 시각은 MySQL 정밀도에 맞춰 `2026-10-30T01:02:03.123456Z`로 저장
- 상품: 1,000원 상품 2개와 2,000원 상품 1개. 두 상품 모두 최초 재고 10개
- 포인트: 사례별 최초 20,000원. 주문 확정 후 16,000원

성공한 사례별 관찰값은 다음 파일에 저장한다. 테스트 메서드 하나만 선택해 실행할 수도 있다.

- `apps/commerce-api/build/reports/week6/order-unpublished.json`
- `apps/commerce-api/build/reports/week6/order-consumed.json`
- Streamer 로그: 같은 디렉터리의 `streamer-<consumer group>.log`

테스트 메서드를 시작할 때 해당 사례의 이전 결과 파일을 삭제한다. 실패 시에도 streamer 로그로 시작·소비 오류를 확인할 수 있다.

## 두 사례의 확인 기준

| 사례 | DB와 발행 경계 | 확인하는 결과 |
| --- | --- | --- |
| 발행 누락 | AFTER_COMMIT에서 별도 DB 연결로 CONFIRMED를 확인한 뒤, 실제 Kafka send를 호출하기 전에 예외 주입 | 주문·재고·포인트 변경은 커밋, 두 파티션의 end offset은 변화 없음, 구매 활동 없음 |
| 정상 처리 | 실제 Kafka send와 구매 활동 consumer 실행 | 브로커 record의 key·전체 payload가 주문 원본과 일치, 전체 품목 저장, 원본 record offset+1 커밋 |

발행 누락은 통제된 예외 주입이며 OS 프로세스 강제 종료 실험은 아니다. 각 사례의 시작·종료 offset을 비교한다. 정상 발행은 record 한 건과 offset 증가 1을 확인하고, 발행 누락은 증가가 없음을 확인한다. 구매 활동 누락 조회도 해당 사례의 사용자로 제한한다.

## 관찰 기록

2026-10-30 18:12 KST의 분리된 두 테스트에서 기록한 값이다. 기준 소스는 `09c1b95`이며, 테스트 리팩터링을 적용한 작업 트리에서 실행했다.

- 토픽: `order-delivery-2a66dbf3-4bcf-4f42-ae09-ac885d1b8125`
- 발행 누락 group: `purchase-delivery-584fd2e3-01c0-441e-98eb-dbfc3a023b39`
- 정상 소비 group: `purchase-delivery-76e83464-e236-429d-9536-13988205c021`
- 최초 확정 시각: `2026-10-30T01:02:03.123456Z`

| 관찰값 | 발행 누락 사례 | 정상 소비 사례 |
| --- | --- | --- |
| orderId | 1 | 2 |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:2` |
| 전체 품목 | 상품 1 × 2, 상품 2 × 1 | 상품 3 × 2, 상품 4 × 1 |
| 주문 상태 / 결제액 | CONFIRMED / 4,000 | CONFIRMED / 4,000 |
| 해당 사용자 포인트 잔액 | 16,000 | 16,000 |
| 해당 상품의 남은 재고 | 상품 1: 8, 상품 2: 9 | 상품 3: 8, 상품 4: 9 |
| 브로커 기록 | 두 파티션 end offset 변화 없음 | partition 0, offset 0 |
| 구매 활동 | 0행 | 2행, 품목·수량·최초 확정 시각 일치 |
| group committed offset | 업무 record 없음 | 1 |

각 사례는 다른 사용자와 상품을 사용하므로 앞선 사례의 재고·포인트 변화가 다음 사례의 기대값에 영향을 주지 않는다. 두 사례의 주문 ID는 이 실행의 관찰값이다.

## 다음 주 재현

격리 테스트의 DB와 토픽은 실행이 끝나면 정리된다. 다음 주에는 같은 명령으로 두 사례를 새로 만든 뒤 사례별 JSON에서 ID와 원본 snapshot을 읽는다. 지난 실행의 ID가 남아 있다고 가정하지 않는다.

발행 누락 주문은 CONFIRMED 원본과 전체 order_item으로 `OrderConfirmed/v1`을 재구성할 수 있다. 재구성 시 해당 실행의 `orderId`, `order-confirmed:v1:<orderId>`, 최초 `confirmedAt`, 상품·수량을 그대로 사용한다. 자동 재발행은 추가하지 않는다. 수동 복구 절차와 중복 처리 검증은 [구매 활동 consumer](purchase-consumer.md)를 따른다.

현재 포인트 결제에는 PG의 `providerOrderId`, `transactionKey`가 없으며 관찰 JSON에도 해당 없음으로 기록한다.
