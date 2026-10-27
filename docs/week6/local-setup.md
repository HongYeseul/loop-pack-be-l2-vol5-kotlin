# 6주차 로컬 실행 준비

현재 주문 확정은 포인트 결제를 사용한다. Kafka 연결 설정과 격리된 broker 테스트까지 준비되어 있으며, 주문 이벤트 발행과 구매 활동 저장은 다음 구현 대상이다.

## 실행 순서

저장소 루트에서 필요한 인프라를 실행한다. 기존 MySQL 3306, Redis 6379·6380, Kafka 19092 포트를 다른 프로젝트가 사용하는지 먼저 확인한다.

```bash
docker compose -f docker/infra-compose.yml up -d --wait
```

3306이 이미 사용 중이면 명령 앞에 `MYSQL_PORT=13306`을 붙인다. API와 streamer 실행에도 같은 값을 지정한다. IDE에서는 각 실행 설정의 환경 변수에 추가한다.

```bash
MYSQL_PORT=13306 docker compose -f docker/infra-compose.yml up -d --wait
MYSQL_PORT=13306 ./gradlew :apps:commerce-api:bootRun
MYSQL_PORT=13306 ./gradlew :apps:commerce-streamer:bootRun
```

MySQL·Redis master/replica·Kafka·Kafka UI를 실행하고, `kafka-init`이 `user-events`, `product-events`, `order-events`를 준비한다. `kafka-init`의 `Exited (0)`은 초기화 성공 상태다. 이미 있는 topic은 삭제하거나 다시 만들지 않는다.

API와 streamer는 컨테이너에 포함하지 않는다. IDE에서 각 Application을 `local` 프로필로 실행하거나, 서로 다른 터미널에서 다음 명령을 실행한다.

```bash
./gradlew :apps:commerce-api:bootRun
```

```bash
./gradlew :apps:commerce-streamer:bootRun
```

| 대상 | 주소 |
| --- | --- |
| 고객·관리자 API | `http://127.0.0.1:8080` |
| API 상태 확인 | `http://127.0.0.1:8081/actuator/health` |
| Streamer | `http://127.0.0.1:8082` |
| Streamer 상태 확인 | `http://127.0.0.1:8083/actuator/health` |
| Kafka UI | `http://127.0.0.1:9099` |
| 호스트 앱의 Kafka 연결 | `localhost:19092` |
| Compose 내부의 Kafka 연결 | `kafka:9092` |

Compose 초기화 시 세 topic을 각각 partition 2개, replica 1개로 준비한다. Producer·consumer·admin은 같은 bootstrap address를 사용한다. 다른 broker를 쓰려면 두 앱에 동일한 `BOOTSTRAP_SERVERS`를 지정하고 해당 broker에 topic을 준비한다.

두 앱의 로컬 JPA 설정은 `update`다. 앱 재시작으로 기존 테이블을 다시 만들지 않는다. 테스트 프로필은 기존 `create` 설정과 격리된 DB를 사용한다.

상태 확인과 종료는 다음 명령을 사용한다. `down`은 데이터 볼륨을 삭제하지 않는다.

```bash
docker compose -f docker/infra-compose.yml ps -a
docker compose -f docker/infra-compose.yml down
```

## Topic별 이벤트와 구독

```mermaid
flowchart TD
    subgraph API["commerce-api — 하나의 서버"]
        User["회원 기능"]
        Product["상품 기능"]
        Order["주문 기능"]
    end

    User --> U[("user-events")]
    Product --> P[("product-events")]
    Order --> O[("order-events")]

    U --> Analytics["분석 Consumer"]
    P --> Analytics
    O --> Analytics
    O --> Purchase["구매 활동 Consumer"]
```

| Topic | 이벤트 예시 | 구독 대상 |
| --- | --- | --- |
| `user-events` | 회원가입 시도 `UserSignUpAttempted`, 회원가입 완료 `UserSignedUp` | 분석 consumer, group `analytics` |
| `product-events` | 상품 상세 조회 `ProductDetailQueried`, 상품 목록 조회 `ProductListQueried` | 분석 consumer, group `analytics` |
| `order-events` | 결제 시도 `PaymentAttempted`, 결제 완료 `PaymentSucceeded`, 주문 확정 `OrderConfirmed` | 분석 consumer와 구매 활동 consumer, 서로 다른 group |

이벤트 종류는 해당 도메인의 enum에서 관리한다. 각 enum의 `TOPIC`은 전송할 topic이고, JSON의 `eventType`에는 `OrderConfirmed` 같은 `eventName` 값이 들어간다. 주문·결제의 현재 상태를 나타내는 status와는 구분한다.

| 도메인 | 이벤트 enum | Topic |
| --- | --- | --- |
| 회원 | [`UserEventType`](../../apps/commerce-api/src/main/kotlin/com/loopers/user/domain/event/UserEventType.kt) | `user-events` |
| 상품 | [`ProductEventType`](../../apps/commerce-api/src/main/kotlin/com/loopers/product/domain/event/ProductEventType.kt) | `product-events` |
| 주문 | [`OrderEventType`](../../apps/commerce-api/src/main/kotlin/com/loopers/order/domain/event/OrderEventType.kt) | `order-events` |

분석 consumer는 세 topic을 구독한다. 구매 활동 consumer는 `order-events` 중 `OrderConfirmed`만 처리하고, 분석 consumer와 서로 다른 group을 사용한다.

회원가입 이벤트 발행 TODO는 `UserKafkaEventPublisher.publishSignedUp` 본문에 두었다. 이 메서드는 미구현 스텁이며, 호출하면 `NotImplementedError`가 발생한다. 회원가입 유스케이스와 기존 요청 흐름에는 아직 연결하지 않았다.

상품은 `ProductFacade.get`의 조회 성공 뒤, 주문은 `OrderFacade.confirm`의 확정 처리 뒤에 발행 연결 TODO를 표시했다. 상품 상세 API 조회 성공은 실제 화면 노출과 구분한다. 주문 이벤트는 메서드 내부에서 즉시 전송하지 않고 TX commit 후 발행해야 한다.

## 적용한 설정

| 대상 | 값 | 동작 |
| --- | --- | --- |
| Producer | `acks=all`, `enable.idempotence=true` | broker 확인과 producer 전송 재시도 중복 방지 사용 |
| 전송 형식 | String key, JSON value | Consumer에서는 JSON bytes를 받아 검증 |
| Consumer | `enable.auto.commit=false` | 주기적인 자동 offset commit 비활성화 |
| 새 group의 시작 위치 | `earliest` | 저장된 offset이 없으면 보존된 첫 record부터 읽기 |
| Record listener | concurrency 1, `MANUAL_IMMEDIATE` | consumer 스레드의 ack 시점에 offset commit |
| Record listener 오류 | 컨테이너 중단 | 실패 record를 완료로 넘기지 않고 원인 확인 후 재시작 |
| Streamer group | `purchase-activity` | 구매 활동 listener가 사용할 기본 group |
| 데모 listener | 비활성화 | 출력 후 ack만 하는 기존 데모는 기본 실행에서 제외 |
| 테스트 프로필 | listener·topic 자동 시작 비활성화 | broker가 필요한 테스트만 fixture로 명시적으로 실행 |

`retries=3`, 요청 timeout 20초, retry 간격 500ms는 기존 설정을 유지했다. Producer의 멱등성 설정이 구매 활동 DB의 중복 저장까지 막아주지는 않는다.

## Consumer 연결 기준

- 구매 활동 listener는 `KafkaConfig.RECORD_LISTENER`를 선택한다.
- Key는 문자열, value는 JSON bytes로 수신한다.
- 자동 offset commit은 꺼져 있다. 업무 DB commit이 끝난 뒤 consumer 스레드에서 ack한다.
- Record listener에서 예외가 발생하면 컨테이너를 중단한다. 오류 원인을 해결하고 같은 group으로 재시작한다.
- 기존 데모 listener는 기본 비활성 상태다. 필요하면 `demo-kafka.enabled=true`로 별도 실행한다.

현재 streamer에는 구매 활동 listener가 아직 없다. `purchase-activity` group은 해당 listener가 연결되고 실행된 뒤 broker에 참여한다.

## Kafka 연결 검증

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaSmokeTest'
```

로컬 개발은 기존 Compose의 `bitnamilegacy/kafka:3.5.1`을 사용한다. 테스트는 Testcontainers로 별도의 `apache/kafka-native:3.8.0` broker를 시작한다. 첫 실행에는 이미지 다운로드가 필요하다. Compose의 Kafka나 업무 topic은 사용하지 않는다. Kafka용 Dockerfile은 추가하지 않았다.

고유한 topic·group에 `SetupProbe`를 보내 실제 record listener의 수신 내용과 committed offset을 확인한다. 구매 활동을 생성하는 테스트는 아니다. 테스트가 끝나면 broker를 정리한다.

일반 테스트 프로필에서는 Kafka listener 자동 시작과 topic 자동 생성을 끈다. Kafka가 필요한 테스트에서만 fixture의 bootstrap address와 시작 설정을 주입한다.

## 다음 구현

- 기존 주문 최초 확정 commit 후 `OrderConfirmed` 발행
- 저장된 품목·수량·확정 시각과 stable eventId 사용
- Streamer의 구매 활동·처리 완료 기록을 같은 DB 트랜잭션으로 저장
- 중복·충돌·rollback·재전달 및 commit 후 발행 누락 검증

단일 broker 세팅으로 다중 broker의 복제 장애 내구성까지 검증할 수는 없다.
