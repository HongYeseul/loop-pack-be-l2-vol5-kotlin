# 구매 활동 consumer

주문 확정·재고·포인트 처리는 API가 맡는다. Streamer는 `OrderConfirmed/v1`을 받아 `purchase_activity`에 상품별 구매 사실을 저장한다.

## 저장과 완료

업무 필드는 `order_id`, `product_id`, `quantity`, `confirmed_at`이다. `(order_id, product_id)`는 unique이며 한 주문의 전체 품목을 같은 트랜잭션으로 저장한다. 구매 활동은 수정·삭제하지 않는다.

기존 구매 활동의 전체 품목·수량·확정 시각이 같으면 재전달로 판단한다. 일부 품목만 있거나 내용이 다르면 충돌로 처리한다. 동시 INSERT 충돌은 rollback한 뒤 먼저 commit된 결과와 비교한다. 별도 처리 이력·상태·Outbox 테이블은 없다.

```text
수신 → JSON 자동 역직렬화 → DTO 계약 검증 → 구매 활동 저장 또는 동일 내용 확인 → DB commit → ack
```

DB commit 후 ack 전에 종료되면 같은 메시지를 다시 받을 수 있다. 구매 활동으로 중복을 판정한다. 최초 확정 시각은 UTC 마이크로초 정밀도로 유지한다.

## 소비와 실패 정책

기본 topic은 `order-events`, group은 `purchase-activity`다. Topic과 DLT는 `commerce.kafka.order-topic`, `commerce.kafka.order-dlt-topic`으로 변경할 수 있다. Group은 `spring.kafka.consumer.group-id`를 사용한다.

전용 record listener는 concurrency 1, `max.poll.records=1`, 자동 커밋 비활성화, `MANUAL_IMMEDIATE`를 사용한다. 기존 공통·데모 listener 설정은 변경하지 않는다. `commerce.kafka.purchase-activity-enabled=false`로 구매 활동 listener만 비활성화한다.

| 실패 | 처리 |
| --- | --- |
| 일시적 DB 연결·트랜잭션 시작·락 대기 실패 | 최초 실행 이후 1초·2초·4초 대기, 최대 3회 재시도 |
| 잘못된 JSON·버전·key·eventId·품목·수량, 내용 충돌 | 즉시 DLT |
| 재시도 소진 | DLT |
| 예상하지 못한 오류 | offset을 넘기지 않고 listener 중단 |

다른 정상 eventType은 저장 없이 ack한다. 지원 버전은 1이며 key는 orderId 문자열, eventId는 `order-confirmed:v1:<orderId>`다. JSON 공백·필드 순서·품목 순서는 내용 비교에 영향을 주지 않는다.

전용 consumer는 `ErrorHandlingDeserializer`가 감싼 `JsonDeserializer`로 `OrderEventMessage` DTO를 받는다. `OrderConfirmed`는 주문 확정 DTO로, 다른 종류는 eventType만 가진 DTO로 변환한다. 추가 필드는 무시하며 주문 확정의 필수 값·key·버전·수량은 `OrderConfirmedValidator`에서 검증한다. 문자열 수량이나 소수 수량을 정수로 자동 보정하지 않는다.

잘못된 JSON과 필드 자료형 오류는 listener 호출 전에 오류 처리기로 전달한다. DTO 변환 후의 검증·저장 실패도 수신 원문을 그대로 DLT에 보관하며, 원문 보관용 내부 헤더는 DLT에 중복 전송하지 않는다.

재시도는 새 DB 트랜잭션으로 수행한다. 프로세스 재시작이나 수동 재발행 시 횟수는 새로 시작한다. 예외 종류가 달라졌다는 이유로 실행 중 횟수를 초기화하지 않는다.

## DLT와 주문 원본

기본 DLT는 `order-events.DLT`다. Compose가 partition 2개, replica 1개로 준비한다. 원본 key·payload bytes를 유지하고 원본 topic·partition·offset, 오류를 헤더에 남긴다. DLT 전송 확인 후 원본 offset을 넘긴다. 두 동작은 원자적이지 않아 DLT에는 중복 record가 생길 수 있다.

DLT 전송이 실패하면 정상 v1 메시지에 한해 같은 DB의 `orders`, `order_item`을 읽는다. 확정 상태·최초 확정 시각·전체 품목·수량이 같고 구매 활동이 아직 없으면 원본으로 복원 가능하다고 판단한다. offset을 넘기되 다음 오류 로그를 남긴다.

주문 원본은 읽기 전용 JPA 엔티티와 JPQL로 조회한다. 조회용 repository는 저장·삭제 메서드를 제공하지 않으며, 두 원본 테이블은 streamer의 자동 DDL 생성·변경·삭제 대상에서 제외한다. 원본 테이블의 스키마는 API에서 관리한다.

```text
구매 활동 미처리, 주문 원본으로 수동 복구 필요
```

원본 확인은 구매 활동 저장 성공이 아니다. 수동 복구 전까지 구매 활동은 없다. 검증 오류·내용 충돌, 원본 부재·불일치, 원본 조회 실패는 이 경로로 넘기지 않고 listener를 중단한다. 앱 프로세스를 종료하는 동작은 아니다.

주문 원본 삭제·변경이나 API와 streamer의 DB 분리 시 이 복구 방식도 변경해야 한다. 현재는 확정 시점 원본을 보존하는 로컬 구성을 전제로 한다. 자동 재발행과 실패 메시지 전용 DB 테이블은 없다.

## 수동 복구

실패 원인을 먼저 해결한다. 아래 명령은 저장소 루트에서 로컬 Compose를 대상으로 실행한다. DLT는 Kafka UI(`http://127.0.0.1:9099`)에서 key·본문·오류 헤더를 확인한다.

### 1. 미처리 주문 조회

```sql
SELECT o.id, o.confirmed_at
FROM orders o
WHERE o.status = 'CONFIRMED'
  AND NOT EXISTS (
    SELECT 1 FROM purchase_activity p WHERE p.order_id = o.id
  )
ORDER BY o.id;
```

DLT 보관 실패와 producer 발행 누락을 모두 찾는다. 정상 소비 중인 주문도 잠깐 조회될 수 있으며 같은 사실의 재발행은 중복 방지를 거친다.

구매 활동이 일부만 있거나 내용이 다르면 자동으로 덮어쓰지 않는다. 아래 조회에서 대상 주문 ID를 지정해 전체 상품·수량·시각을 비교한다.

```sql
SELECT i.product_id, SUM(i.quantity) AS quantity, o.confirmed_at
FROM orders o JOIN order_item i ON i.order_id = o.id
WHERE o.id = 42
GROUP BY i.product_id, o.confirmed_at
ORDER BY i.product_id;

SELECT product_id, quantity, confirmed_at
FROM purchase_activity WHERE order_id = 42 ORDER BY product_id;
```

### 2. 선택한 주문의 메시지 생성

`42`를 확인한 주문 ID로 바꾼다. 현재 상품 정보가 아니라 주문 당시 품목으로 메시지를 만들며 eventId와 최초 확정 시각을 유지한다. 출력 파일의 각 행은 `key<TAB>JSON`이다.

```bash
docker compose -f docker/infra-compose.yml exec -T mysql \
  mysql -uapplication -papplication --batch --raw --skip-column-names loopers \
  > /tmp/loopers-order-replay.tsv <<'SQL'
SELECT CONCAT(o.id, CHAR(9), JSON_OBJECT(
  'eventType', 'OrderConfirmed',
  'schemaVersion', 1,
  'eventId', CONCAT('order-confirmed:v1:', o.id),
  'orderId', o.id,
  'confirmedAt', DATE_FORMAT(o.confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
  'items', (
    SELECT JSON_ARRAYAGG(JSON_OBJECT('productId', i.product_id, 'quantity', i.quantity))
    FROM (
      SELECT order_id, product_id, SUM(quantity) AS quantity
      FROM order_item GROUP BY order_id, product_id
    ) i WHERE i.order_id = o.id
  )
))
FROM orders o
WHERE o.id = 42 AND o.status = 'CONFIRMED' AND o.confirmed_at IS NOT NULL;
SQL

cat /tmp/loopers-order-replay.tsv
```

### 3. 확인한 메시지 재발행

```bash
docker compose -f docker/infra-compose.yml exec -T kafka \
  kafka-console-producer.sh --bootstrap-server kafka:9092 --topic order-events \
  --property parse.key=true --property key.separator=$'\t' \
  < /tmp/loopers-order-replay.tsv
```

대상 주문의 구매 활동이 원본과 일치하는지 확인한다. 반복 발행해도 구매 활동 수가 늘어나면 안 된다. 재발행은 새 offset을 받으며 원본 offset이나 새 eventId를 지정하지 않는다.

Listener가 중단됐다면 원인을 해결한 뒤 같은 group으로 streamer를 재시작한다. Group을 바꾸거나 offset을 임의로 넘겨 복구하지 않는다.

## 검증

```bash
./gradlew :apps:commerce-streamer:check
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check
```

`OrderEventJsonDeserializerTest`는 JSON 역직렬화, `OrderConfirmedValidatorTest`는 DTO의 업무 계약, `ConfirmedPurchaseTest`는 저장된 활동과의 내용 비교를 각각 검증한다. `PurchaseActivityPersistenceTest`는 실제 MySQL의 unique·동시 저장·rollback, `PurchaseKafkaIntegrationTest`는 격리된 Kafka·MySQL의 재시도·DLT·commit 전후·재시작·원본 재발행을 검증한다. `OrderSourceJpaRepositoryTest`는 JPQL 집계·읽기 전용 매핑·주문 원본 스키마 보존을 검증한다. `PurchaseArchitectureTest`는 계층 의존을 검사한다. 로컬 업무 topic이나 DB를 테스트에 사용하지 않는다.
