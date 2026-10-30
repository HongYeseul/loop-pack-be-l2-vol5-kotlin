# Kafka key와 consumer 배정 실험

## 재현

저장소 루트에서 실행한다. Docker가 필요하며, Compose의 Kafka와 업무 topic은 사용하지 않는다.

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaAssignmentExperimentTest' --rerun-tasks
```

`KafkaAssignmentExperimentTest`는 key 배정과 consumer 증설을 각각 별도 테스트로 검증한다. Testcontainers의 `apache/kafka-native:3.8.0` broker 하나를 사용하고, 테스트마다 고유한 topic을 만든다. Consumer 실험은 고유한 group도 만든다. Topic은 partition 2개, replica 1개다. Producer·consumer·admin은 같은 fixture의 bootstrap address를 사용한다. Consumer와 topic은 테스트에서 닫고 삭제하며, broker는 Testcontainers가 종료한다.

실행별 key·partition·offset과 group 배정·수신 결과는 다음 파일에 저장된다. 테스트를 시작할 때 이전 결과 파일을 지우고, 모든 검증이 성공한 뒤 새 결과를 저장한다.

```text
apps/commerce-streamer/build/reports/kafka-experiments/key-routing.txt
apps/commerce-streamer/build/reports/kafka-experiments/consumer-assignment.txt
```

## 확인하는 동작

1. `routesSameKeyToSamePartition`: Producer가 partition을 지정하지 않고 같은 key `42`를 세 번 발행한다. 실제 전송 결과의 partition이 같은지 확인한다. 다른 key도 발행하되, 서로 다른 partition이어야 한다는 조건은 두지 않는다.
2. `reassignsPartitionsWhenConsumersJoin`: 같은 group에 consumer를 하나씩 추가해 1→2→3개로 늘린다. Consumer마다 별도 스레드에서 `subscribe`와 `poll`을 수행한다.
3. AdminClient의 group 상태가 `STABLE`이고 실제 member 수와 각 consumer의 로컬 assignment가 일치할 때까지 제한 시간 안에서 기다린다. 두 partition이 각각 한 member에만 배정됐는지도 확인한다.
4. 각 단계에서 두 partition에 한 건씩 지정 발행한다. 실제 수신 consumer·partition·offset이 해당 단계의 배정과 발행 metadata에 일치하는지 확인하고, 수신 후 수동 commit된 offset이 `record.offset + 1`인지 확인한다.

실험에서는 `RangeAssignor`를 명시해 한 topic의 분배를 비교한다. 배정된 consumer의 이름 자체는 보장하지 않는다. Partition 2개이므로 배정 개수는 각각 `[2]`, `[1, 1]`, `[0, 1, 1]`이며 세 번째 단계에는 idle consumer가 하나 생긴다. `sleep` 경과나 consumer 객체 수만으로 배정 완료를 판정하지 않는다.

## 측정 결과

2026-10-30 18:05 KST 실행에서 관측한 값이다. 기준 commit `09c1b95`에 분리한 실험 테스트를 추가한 작업 트리의 관찰값이다.

- Key 실험 topic: `key-routing-f972b8ba-1ebb-43da-a70d-610158464f73`
- Consumer 실험 topic: `consumer-assignment-cac5ada5-5373-49dd-86cd-cf8eb5361b83`
- Consumer group: `partition-experiment-e8f6a877-3e70-4e0c-bcd1-23999d289f56`

| Key | 실제 partition | 실제 offset |
| --- | --- | --- |
| `42` 세 번 | `0`, `0`, `0` | `0`, `1`, `2` |
| `7` | `1` | `0` |
| `8` | `1` | `1` |
| `9` | `1` | `2` |

| Consumer 수 | 실제 배정 | 각 partition에서 수신한 offset | 수신 후 committed offset |
| --- | --- | --- | --- |
| 1 | consumer-1: `[0, 1]` | p0: `0`, p1: `0` | p0: `1`, p1: `1` |
| 2 | consumer-1: `[0]`, consumer-2: `[1]` | p0: `1`, p1: `1` | p0: `2`, p1: `2` |
| 3 | consumer-1: `[0]`, consumer-2: `[1]`, consumer-3: `[]` | p0: `2`, p1: `2` | p0: `3`, p1: `3` |

각 단계의 group 상태는 `Stable`이었다. 세 번째 단계에서 consumer-3은 partition이 없어 메시지를 받지 않았다. 다른 key `7`·`8`·`9`가 모두 같은 partition으로 배정되는 것도 관측했다. 실행이 끝난 topic·group·broker는 이후 실습에서 그대로 재사용할 대상이 아니며, 재실행 결과는 새 결과 파일을 기준으로 확인한다.

## 해석 범위

같은 key의 배정 결과는 이 실험의 partition 수와 producer partitioner를 전제로 한다. Topic partition 수나 partitioner가 바뀌면 배정도 달라질 수 있다. 서로 다른 key도 같은 partition으로 갈 수 있다.

`acks=all`, `enable.idempotence=true`, 자동 offset commit 비활성화 상태로 실행한다. Broker 하나와 replica 1개에서 받은 전송 성공은 다중 broker의 복제 장애 내구성을 검증한 결과가 아니다. 이 테스트의 문자열 probe는 구매 활동을 생성하지 않으며, DB commit과 ack의 순서·업무 중복 방지는 구매 활동 consumer 통합 테스트에서 확인한다.
