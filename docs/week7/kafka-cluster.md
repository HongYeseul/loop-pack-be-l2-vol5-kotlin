# Kafka 복제 실험 실행

`docker/kafka-cluster-compose.yml`은 복제 수 3, 최소 ISR 2인 별도 실험 구성이다. 기존 단일 broker와 호스트 포트 19092·9099가 겹치므로 동시에 실행하지 않는다. 사용 중인 컨테이너는 자동으로 종료하지 않는다.

```bash
docker compose -f docker/kafka-cluster-compose.yml config --quiet
docker compose -f docker/kafka-cluster-compose.yml up -d --wait
docker compose -f docker/kafka-cluster-compose.yml ps -a
```

호스트의 bootstrap 주소는 `localhost:19092,localhost:19093,localhost:19094`, Kafka UI는 `http://127.0.0.1:9099`다. 앱에는 같은 `BOOTSTRAP_SERVERS`를 지정한다. 초기화가 만드는 topic은 partition 2개, 복제 수 3이다. 기존 볼륨에 존재하는 topic의 설정은 `--if-not-exists`로 바뀌지 않으므로 describe 결과를 확인한다.

```bash
docker compose -f docker/kafka-cluster-compose.yml down
```

종료 명령에는 `-v`를 붙이지 않는다. 이 구성은 실행 준비이며 broker 중단·리더 전환을 검증한 결과가 아니다.
