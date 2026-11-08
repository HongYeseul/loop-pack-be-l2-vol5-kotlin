# 로컬에서 주문 이벤트 흐름 눈으로 보기

IntelliJ에서 API, relay, streamer를 띄우고, 주문을 확정하면서 서비스 로그, DB(outbox 포함), Kafka가 각각 어떻게 바뀌는지 본다. 확정 트랜잭션은 outbox 행만 남기고, commerce-relay가 그 행을 Kafka로 보낸다. 구조는 [outbox.md](outbox.md)에 있다.

관찰값은 2026-11-05에 돌려서 확인한 결과다. 2, 5, 6번은 직접 발행 때 본 것이고, 이 부분은 발행 방식과 상관없어 outbox로 바뀐 뒤에도 같다. 1, 4, 7, 8, 9번은 relay로 바꾼 뒤 본 것이다. 3번의 "120초 안에 돌아오면 보내진다", 10, 11번처럼 "직접 해 볼 것"이나 "예상"으로 표시한 곳은 아직 로컬에서 돌려 보지 않았다.

## 처음부터 다시 하기

앞선 실행에서 남은 주문, 메시지, offset이 있으면 offset 번호나 lag가 아래 관찰값과 달라진다. 깨끗한 상태에서 시작하려면 IntelliJ에서 두 앱을 Stop한 뒤 볼륨까지 지우고 다시 올린다.

```bash
docker compose -f docker/infra-compose.yml down -v
```

```bash
MYSQL_PORT=13306 docker compose -f docker/infra-compose.yml up -d --wait
```

`-v`는 MySQL, Kafka, Redis 데이터를 모두 지운다. 다시 올리면 `kafka-init`이 네 topic을 파티션 2개로 새로 만들고, 앱을 띄우면 테이블도 새로 생긴다. 그다음 아래 준비 순서를 그대로 따라가면 된다.

## 준비

### 인프라

```bash
MYSQL_PORT=13306 docker compose -f docker/infra-compose.yml up -d --wait
```

이미 떠 있으면 다시 올릴 필요는 없다. DLT topic이 파티션 2개로 있는지는 꼭 확인한다.

```bash
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events.DLT
```

DLT로 보낼 때 원본과 같은 파티션 번호를 쓰기 때문에, DLT 파티션이 원본보다 적으면 DLT 전송이 실패하고 구매 활동 listener가 멈춘다. topic이 없으면 브로커가 첫 전송 때 파티션 1개로 자동 생성해 버리므로 같은 문제가 생긴다. 1개로 되어 있으면 2개로 늘린다.

```bash
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --alter --topic order-events.DLT --partitions 2
```

### IntelliJ 실행 설정

| 실행 설정 | 프로필 | 환경 변수 |
|---|---|---|
| `CommerceApiApplication` | `local` | `MYSQL_PORT=13306` |
| `CommerceRelayApplication` | `local` | `MYSQL_PORT=13306` |
| `CommerceStreamerApplication` | `local` | `MYSQL_PORT=13306` |

세 설정을 Services 창에 함께 올려 두면 로그를 나란히 볼 수 있다. API가 처음 뜰 때 `ddl-auto: update`로 outbox를 포함한 테이블이 생긴다. relay는 테이블을 만들지 않고 `ddl-auto: validate`로 매핑만 확인하므로 API를 먼저 띄운다. relay는 웹 서버가 없어 포트를 쓰지 않는다.

### DB 연결과 시드

IntelliJ Database 창에서 MySQL을 연결한다.

| 항목 | 값 |
|---|---|
| Host, Port | `localhost`, `13306` |
| User, Password | `application`, `application` |
| Database | `loopers` |

API를 한 번 띄워 테이블이 생긴 뒤 [order-events-seed.sql](../../http/commerce-api/order-events-seed.sql)을 실행한다. 회원가입 API가 없고 로컬 관리자 API는 인증 수단이 없어서 사용자와 상품은 SQL로 넣는다. 다시 실행하면 재고만 100으로 채운다.

- 사용자: `observer1` (id 9001)
- 상품: 9001 (1,000원), 9002 (2,000원), 재고 각 100

요청은 [order-events.http](../../http/commerce-api/order-events.http)를 위에서부터 실행한다. 주문 생성 응답의 id가 `orderId`로 저장되어 확정 요청에 들어간다. 주문은 만든 지 10분이 지나면 만료되니 확정은 그 안에 한다.

## 볼 곳

| 보고 싶은 것 | 어디서 |
|---|---|
| outbox 행이 나가는 과정 | Database 창의 `outbox` (`status`, `attempt_count`, `last_error`, `claimed_by`) |
| 발행 성공, 재시도, 중단 | relay 로그의 `OutboxRelay` |
| 소비, DLT 보관, 소비 중단 | streamer 로그의 `PurchaseActivityRecovery` |
| 주문 상태, 구매 활동 | Database 창의 `orders`, `purchase_activity` |
| 메시지 key, 파티션, offset, 본문 | Kafka UI(http://127.0.0.1:9099) Topics → `order-events` → Messages |
| consumer가 어디까지 읽었나 | Kafka UI Consumers → `purchase-activity` |
| DLT에 간 이유 | Kafka UI Topics → `order-events.DLT` → 메시지 헤더 |

Kafka UI 첫 화면의 파티션 수가 58개로 보이는 건 내부 topic인 `__consumer_offsets`의 파티션 50개가 함께 세어지기 때문이다. 업무 topic 네 개는 각 2개씩 8개다. Topics 목록에서는 내부 topic이 기본으로 숨겨져 있다.

Kafka UI 대신 브로커 안에서 직접 보려면 Docker Desktop에서 `kafka` 컨테이너의 Exec 탭을 열고 아래 명령을 쓴다.

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events --from-beginning --property print.key=true --property print.partition=true --property print.offset=true --property print.headers=true
```

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group purchase-activity
```

DB를 볼 때 자주 쓰는 쿼리다.

```sql
SELECT id, status, paid_amount, confirmed_at FROM orders ORDER BY id DESC;
SELECT id, event_id, status, attempt_count, next_attempt_at, claimed_by, last_error FROM outbox ORDER BY id DESC;
SELECT order_id, product_id, quantity, confirmed_at FROM purchase_activity ORDER BY id DESC;
```

## 시나리오

### 1. 정상 흐름

포인트 충전, 주문 생성, 확정을 차례로 실행한다.

- DB: 확정 직후 `outbox`에 `PENDING` 행이 생기고, 1초 안에 `PUBLISHED`로 바뀐다
- relay 로그: `outbox 발행 완료: id=1, eventId=order-confirmed:v1:1`
- Kafka UI: `order-events`에 key `1`인 메시지. key가 주문 id라 같은 주문은 늘 같은 파티션으로 간다
- streamer 로그: `purchase_activity` insert 두 번 (품목 수만큼)
- DB: `orders` 1번이 `CONFIRMED`, `purchase_activity`에 두 행
- consumer group: 1번 파티션 lag 0

### 2. 같은 주문을 다시 확정

확정 요청을 한 번 더 보낸다.

- 응답: `ORDER_NOT_DRAFT`
- outbox 행도, 새 메시지도 생기지 않는다. 쓰기 쪽 상태 확인이 요청 중복을 막는다

### 3. 브로커를 잠깐 멈췄다가 켜기

relay가 이미 한 번 이상 보낸 상태에서(토픽 메타데이터를 알고 있는 상태) Docker Desktop에서 `kafka`를 Stop하고 확정한다. 10~20초 안에 다시 Start한다.

- 응답: 바로 `CONFIRMED`. 주문 확정은 Kafka를 기다리지 않는다
- relay 로그: producer의 `Node 1 disconnected.`, `Connection to node 1 ... could not be established`가 1초 간격으로 이어진다. relay가 실패를 처리한 로그가 아니라 producer가 다시 연결하려는 흔적이다
- DB: 브로커가 멈춰 있는 동안 outbox 행은 `PENDING`, `attempt_count` 0 그대로다. relay 스레드는 `flush()`에서 결과를 기다리고 있다
- 브로커가 120초 안에 돌아오면 그대로 보내지고 `PUBLISHED`가 된다 (예상)

### 4. 브로커를 120초 넘게 멈추기

3번과 같이 하되 2분 넘게 기다린다.

- 응답: 바로 `CONFIRMED`
- DB: 확정하고 약 2분 동안 `attempt_count`가 0이다가, 2분쯤 지나 1이 된다 (관찰: 11:16:10에 생긴 행이 11:18:11에 1). 그 뒤로도 약 2분마다 1씩 오른다
- relay 로그: 2분마다 `outbox 발행 재시도 예약: ... attemptCount=1, ... error=KafkaProducerException: Failed to send <- ...`. `error`와 `last_error`에는 안쪽 원인까지 남는다. 원인 기록을 넣기 전에 본 값은 `KafkaProducerException: Failed to send`뿐이었고, 넣은 뒤의 문구는 아직 로컬에서 보지 않았다
- 계속 멈춰 두면 10번째 실패에서 `FAILED`가 되고 `outbox 발행 중단` ERROR가 남는다 (관찰: 4번 행이 `attempt_count` 10, `FAILED`). 메타데이터를 알고 있으면 한 번 시도에 120초씩 걸려 `FAILED`까지 약 24분 걸린다
- 브로커가 그 전에 돌아오면 다음 시도에서 보내지고 `PUBLISHED`가 된다
- 결과: 유실 없음. 직접 발행일 때는 120초 뒤 실패 로그만 남고 이벤트가 사라졌다. 왜 2분씩 기다리는지는 outbox-plan.md의 "마지막에 다시 볼 것"에 정리했다

### 5. streamer를 끄고 확정하기

streamer를 Stop한 뒤 주문을 확정하고, 다시 Start한다.

- 끈 동안: 메시지는 topic에 있고 consumer group lag가 1
- 켠 뒤: 그 메시지를 읽고 lag가 0으로 돌아옴

### 6. 계약에 맞지 않는 이벤트 넣기

Exec 탭이나 터미널에서 eventId가 틀린 메시지를 넣는다. Kafka UI의 Produce Message로 넣어도 된다.

```bash
echo '3|{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"wrong-id","orderId":3,"confirmedAt":"2026-11-05T03:54:37.000000Z","items":[{"productId":9002,"quantity":1}]}' | docker exec -i kafka kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events --property parse.key=true --property key.separator='|'
```

- streamer 로그: `구매 활동 DLT 보관: topic=order-events, partition=1, offset=1`
- `order-events.DLT` 같은 파티션 번호에 메시지가 생기고, 헤더 `kafka_dlt-exception-message`에 `eventId가 주문 확정 계약과 다릅니다.`가 남음
- 재시도 없이 바로 DLT로 간다. 잘못된 계약은 다시 해도 안 되는 실패로 분류되기 때문이다

DLT 파티션이 1개뿐이면 여기서 DLT 전송이 실패하고 `구매 활동 소비 중단` 로그와 함께 listener가 멈춘다. 준비 단계의 파티션 확인을 건너뛰었다면 이 장면을 보게 된다.

### 7. relay를 끄고 확정하기

relay를 Stop한 뒤 주문을 확정하고, 다시 Start한다.

- 끈 동안: `outbox` 행이 `PENDING`으로 쌓이고 Kafka에는 아무것도 없다. 주문 확정은 정상이다
- 켠 뒤: 몇 초 안에 쌓인 행이 `PUBLISHED`로 바뀌고 구매 활동까지 저장된다

### 8. 브로커가 멈춘 채로 relay를 새로 띄우기

브로커를 Stop하고 relay를 재시작한 뒤 확정한다.

- relay가 아직 토픽 메타데이터를 모르는 상태라, `send()`가 메타데이터를 5초(`max.block.ms`) 기다리다 바로 실패한다
- DB: `attempt_count`가 백오프(1, 2, 4, 8, 16초…) 간격으로 빠르게 오른다 (관찰: 행이 생기고 1분 36초 뒤 6). 관찰 당시 `last_error`는 `KafkaException: Send failed`였고, 지금은 안쪽 원인까지 함께 남는다
- 4번과 같은 브로커 장애인데 실패가 기록되는 속도가 다른 이유는 메타데이터를 이미 알고 있는지 여부다

### 9. relay 두 대 띄우기

`CommerceRelayApplication` 실행 설정에서 "Allow multiple instances"를 켜고 두 번 실행한다. 포트를 쓰지 않으니 따로 바꿀 값은 없다.

실습 전용 토픽을 만들고 [outbox-seed.sql](../../http/commerce-relay/outbox-seed.sql)로 `PENDING` 행 2000개를 넣는다. 이 토픽은 streamer가 읽지 않아 구매 활동에 영향이 없다.

```bash
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic outbox-relay-lab --partitions 2 --replication-factor 1
```

```bash
docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers < http/commerce-relay/outbox-seed.sql
```

볼 것

- 두 relay 로그가 서로 다른 id를 `발행 완료`로 남긴다
- 다 나간 뒤 relay별 처리 수와 Kafka UI의 `outbox-relay-lab` 메시지 수(2000)

관찰 (2026-11-05)

- `claimed_by`: `local-fe03637f` 1000행, `local-e8e8e4f0` 1000행, 모두 `PUBLISHED`. 약 16초 걸렸다
- Kafka: 파티션 0에 990건, 1에 1010건, 합계 2000건. 행 수와 같아 같은 행이 두 번 나간 경우가 없다
- 두 relay가 local 배치 크기(10) 단위로 주문을 번갈아 가져갔다. 첫 배치의 `published_at`은 20ms 차이였다
- 주문 1000개 모두 첫 이벤트가 두 번째보다 먼저 `PUBLISHED`가 됐고, 두 이벤트가 같은 배치에 함께 잡힌 주문은 없었다. 162개 주문은 두 번째 이벤트를 다른 relay가 가져갔다. Kafka에서도 모든 주문이 한 파티션 안에서 첫 이벤트, 두 번째 이벤트 순으로 쌓였다
- 시드는 같은 주문에 서로 다른 이벤트 2개(`event_id`가 다름)를 넣어 같은 key의 순서 조건을 보려고 만든 것이다. 같은 이벤트의 중복이 아니다

```sql
SELECT claimed_by, status, COUNT(*) FROM outbox WHERE topic = 'outbox-relay-lab' GROUP BY claimed_by, status;
```

### 10. 보내는 중인 relay를 붙잡아 lease 만료 보기 (직접 해 볼 것)

lease 기본값은 150초라, 실습할 때만 두 relay 모두 환경 변수 `OUTBOX_RELAY_LEASE=20s`로 줄인다.

1. relay A의 `OutboxRelay.runOnce`에서 `outboxPublisher.publish(events)` 다음 줄에 브레이크포인트를 걸고 Suspend를 Thread로 둔다
2. 주문을 확정한다. A가 행을 `CLAIMED`로 잡고 Kafka로 보낸 뒤 결과를 기록하기 전에 멈춘다
3. 20초가 지나면 relay B가 같은 행을 다시 선점해 보낸다. `claimed_by`가 B로 바뀌고 `PUBLISHED`가 된다
4. Kafka UI에는 같은 key의 메시지가 두 건, `purchase_activity`에는 한 번만 저장된다. 중복은 consumer가 흡수한다
5. A를 풀면 결과 기록이 0건이 되고 `outbox 완료 기록 누락, 다른 relay 가 다시 선점했거나 이미 처리함` 경고가 남는다

### 11. FAILED 행 재개하기 (직접 해 볼 것)

4번에서 브로커를 오래 멈춰 두면 `FAILED`가 된 행이 남는다 (관찰: 주문 5의 `order-confirmed:v1:5`, `attempt_count` 10, 구매 활동 없음). 브로커가 다시 떠 있는 상태에서 relay 실행 설정의 프로그램 인자에 재개할 `event_id`를 넣고 띄운다.

```
--outbox.resume.event-ids=order-confirmed:v1:5
```

- relay 로그: `outbox 재개: eventId=order-confirmed:v1:5`, 이어서 `outbox 발행 완료`
- DB: 행이 `PENDING`(`attempt_count` 0)을 거쳐 `PUBLISHED`가 되고, `last_error`에는 이전 실패 원인이 남는다
- `purchase_activity`에 주문 5의 품목이 생긴다
- 재개가 끝나면 프로그램 인자를 지운다. 남겨 두면 다음 실행 때도 같은 `event_id`로 재개를 시도한다 (이미 `PUBLISHED`라 경고만 남기고 아무것도 바꾸지 않는다)

## 정리

IntelliJ에서 세 앱을 Stop한다. 인프라를 내려도 데이터 볼륨은 남는다.

```bash
docker compose -f docker/infra-compose.yml down
```
