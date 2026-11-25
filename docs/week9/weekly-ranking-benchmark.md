# 주간 랭킹 집계 벤치마크

ScoreStep의 item, 읽기 방식, 집계 위치에 따른 비용을 비교한다. 실험 Step은 `src/main`의 랭킹 벤치마크 패키지에 있으며 `ranking-benchmark` 프로필에서 등록된다. 테스트가 ScoreStep과 기존 PublishStep을 조합해 Job을 만든다.

| 방식 | item | 조회와 쓰기 |
| --- | --- | --- |
| A | 구매 한 행 | 구매 PK 순으로 100행씩 읽고 청크별 부분합을 누적 |
| B | 상품 ID | 상품 ID 100개마다 `IN … GROUP BY` 한 번으로 최종 점수를 읽어 저장 |
| C-Cursor | 상품별 점수 | `GROUP BY` 쿼리 결과를 Cursor로 읽어 저장 |
| C-Paging | 상품별 점수 | `GROUP BY` 결과를 상품 ID 순으로 100행씩 읽어 저장 |
| D | 해당 없음 | Tasklet 한 트랜잭션에서 `INSERT … SELECT … GROUP BY` 실행 |

## 실행

Docker를 실행한 뒤 저장소 루트에서 아래 명령을 실행한다. 테스트는 전용 MySQL 8.0.46 컨테이너를 사용한다. Batch 메타데이터와 점수 쓰기는 동일한 JDBC 트랜잭션 매니저를 사용한다.

```bash
./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingScoreBenchmarkTest'
```

성능 측정은 `RUN_RANKING_BENCHMARK=true`로 켠다. `--rerun`은 Gradle의 이전 테스트 결과 재사용을 건너뛴다.

```bash
RUN_RANKING_BENCHMARK=true ./gradlew :apps:commerce-batch:test \
  --rerun --tests '*WeeklyRankingScoreBenchmarkTest' --info
```

| 환경 변수 | 값 |
| --- | --- |
| `RANKING_BENCHMARK_SUITE` | 기본 `smoke`는 상품 1천 개·구매 1만 행. `full`은 전체 규모. `optimized`는 상품 1천 개·구매 100만 행에 커버링 인덱스 추가 |
| `RANKING_BENCHMARK_CASE` | `1000x1000000`처럼 상품 수와 구매 행 수를 지정. 쉼표로 여러 규모 선택 |
| `RANKING_BENCHMARK_METHODS` | `A,B`처럼 선택한 방식만 실행. 기본값은 다섯 방식 |
| `RANKING_BENCHMARK_DISTRIBUTION` | `uniform` 또는 `skewed` 분포만 실행 |
| `RANKING_BENCHMARK_PK_ORDER` | `grouped` 또는 `shuffled` PK 순서만 실행 |
| `RANKING_BENCHMARK_TRIAL=true` | 적재와 완주를 확인하기 위해 예열 없이 방식별 1회 실행 |
| `RANKING_BENCHMARK_OUTPUT` | 결과 기본 경로는 commerce-batch의 `build/weekly-ranking-benchmark`. 각 실행에 별도 디렉터리 생성 |

`full`은 구매 100만 행에서 상품 수 100·1천·1만·10만·100만 개를 비교한다. 상품 수 1천 개에서는 구매 행을 1만·10만·100만·1천만으로 바꾼다. 겹치는 규모 조건은 한 번 실행한다. 각 규모는 균등과 상위 1%에 구매 행 80%가 집중된 분포를 상품별 연속 PK 순서와 섞인 PK 순서로 생성한다. 같은 분포에서는 구매 내용과 수량을 유지한 채 순서만 변경한다. 모든 상품에 최소 한 건이 필요한 조건에서 편향을 만들 수 없으면 사유를 `skipped.csv`에 기록한다.

## 실행 조건과 지표

- 다섯 방식은 한 조건에서 같은 스냅샷을 읽는다. 기본 인덱스는 운영 랭킹 스키마에 구매 PK 고유 인덱스를 추가한 구성이다. `optimized`는 구매 PK 조회용과 상품 집계용 커버링 인덱스를 추가한다.
- 반복 측정 중 로그가 쌓이는 양을 줄이기 위해 전용 MySQL 컨테이너의 바이너리 로그를 비활성화한다. 실행 설정은 `environment.txt`의 `mysql_log_bin`에 기록한다.
- 1회 예열 후 5회 측정하며 회차마다 첫 실행 방식을 순환한다. 매 실행 전에 결과를 초기화한다. 입력 생성, 스냅샷 복사, 결과 초기화, 순위 공개 시간은 ScoreStep과 별도로 기록한다.
- Cursor는 Connector/J 기본 fetch 설정을 사용한다. Paging의 페이지 크기와 청크 Step의 청크 크기는 각각 100이다. Cursor의 heap에는 JDBC 결과 버퍼도 포함될 수 있다.
- `runs.csv`의 `read_count`와 `write_count`는 Spring Batch item 단위다. A는 구매 행, B는 상품 ID, C는 상품 점수다. D에는 청크 item이 없다. `result_rows`는 결과 테이블의 상품 행 수다. `data_chunks`는 실제 item을 읽고 커밋한 청크 수이며 `commit_count`에는 종료 확인 트랜잭션도 포함될 수 있다.
- JDBC 실행과 JDBC batch 호출은 테스트 DataSource에서 센다. `sql.csv`는 MySQL Performance Schema digest의 구간 차이를 사용하며 랭킹 SQL과 `BATCH_*` 메타데이터 SQL을 구분한다. MySQL의 `rows_affected`는 upsert에서 갱신 행을 2로 셀 수 있으므로 상품 행 수는 `result_rows`로 확인한다.
- `pool_longest_checkout_ms`는 ScoreStep 구간에 HikariCP에서 반환된 커넥션의 최대 점유 시간이다.
- JVM heap은 50ms 주기의 최대 관측값이다. JVM CPU는 프로세스 CPU 시간, MySQL CPU는 SQL 문장 스레드 CPU 시간이다. I/O는 컨테이너에서 관측한 InnoDB 파일 바이트, 전송량은 MySQL 세션의 송수신 바이트다. 계측하지 못한 값은 비워 두고 사유는 `unavailable-metrics.txt`와 환경 정보에서 확인한다.
- `explain/`에는 ScoreStep 측정 후 수집한 주요 SQL 실행 계획이 저장된다. Paging의 첫 페이지와 대표 후속 페이지를 기록한다. D의 쓰기 SQL은 일반 `EXPLAIN`, 집계 SELECT는 `EXPLAIN ANALYZE`를 사용한다.

## 실측 결과 정리

측정된 CSV가 생성되면 Python 3와 `matplotlib`로 그래프와 분석용 Markdown을 생성한다.

```bash
python3 docs/week9/plot_weekly_ranking_benchmark.py apps/commerce-batch/build/weekly-ranking-benchmark/run-...
```

`analysis.md`에서 완주한 회차의 중앙값과 실행 시간 범위를 확인한다. C-Cursor와 C-Paging은 같은 상품 집계에서 Reader 구현의 영향을 비교한다. A와 B의 차이는 item, 조회, 집계 위치, 쓰기 패턴이 함께 바뀐 결과다. P95 청크 시간, 반환 행 수, 누적 upsert 횟수, 메타데이터 비용과 실행 계획을 함께 보고 원인을 판단한다.
