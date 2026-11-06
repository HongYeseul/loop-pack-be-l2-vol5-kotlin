-- relay 여러 대 실습용 PENDING 행: 주문 1000개 x 2행
-- streamer 가 읽지 않는 실습 전용 토픽으로 보낸다. 먼저 토픽을 만든다.
--   docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic outbox-relay-lab --partitions 2 --replication-factor 1
-- 실행
--   docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers < http/commerce-relay/outbox-seed.sql
-- 앱은 시각을 UTC 로 저장하므로 UTC_TIMESTAMP 사용

SET @run = UNIX_TIMESTAMP();
SET SESSION cte_max_recursion_depth = 5000;

INSERT INTO outbox (
    created_at, updated_at, event_id, aggregate_type, aggregate_id, event_type,
    schema_version, topic, payload, status, attempt_count, next_attempt_at
)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6),
    CONCAT('lab-', @run, '-', n),
    'ORDER',
    CONCAT('lab-', @run, '-', CEIL(n / 2)),
    'OrderConfirmed',
    1,
    'outbox-relay-lab',
    CONCAT('{"order":"lab-', @run, '-', CEIL(n / 2), '","sequence":', 2 - MOD(n, 2), '}'),
    'PENDING',
    0,
    UTC_TIMESTAMP(6)
FROM seq;

-- 확인: relay 별로 몇 행을 맡았는지, 아직 안 나간 행이 있는지
-- SELECT claimed_by, status, COUNT(*) FROM outbox WHERE topic = 'outbox-relay-lab' GROUP BY claimed_by, status;
