# 쿠폰 로컬 실행과 수동 재개

## 실행

기존 인프라를 유지한다. 공유 Redis를 장애 실험에 사용하거나 기존 볼륨을 삭제하지 않는다. API와 consumer는 같은 MySQL·Redis primary·키 접두어·consumer group을 사용해야 한다.

```bash
: "${COUPON_OPERATIONS_USERNAME:?운영자 계정을 지정하세요}"
: "${COUPON_OPERATIONS_PASSWORD:?운영자 비밀번호를 지정하세요}"
MYSQL_PORT=13306 SPRING_PROFILES_ACTIVE=local \
  ./gradlew :apps:commerce-api:bootRun \
  --args='--coupon.fixture.enabled=true --coupon.operations.enabled=true'
```

`CouponLocalFixture`가 사용자 couponuser와 총량 100장의 로컬 행사를 준비한다. 기존 계정·행사·발급 결과는 초기화하지 않는다. 로그의 실제 campaignId를 확인한다. local fixture·운영 API의 기본값은 비활성이다.

다른 터미널에서 consumer를 실행한다. commerce-streamer의 기존 Kafka 소비 설정도 로딩하므로 기존 Kafka 인프라가 필요하다.

```bash
MYSQL_PORT=13306 SPRING_PROFILES_ACTIVE=local \
  ./gradlew :apps:commerce-streamer:bootRun \
  --args='--coupon.consumer.enabled=true'
```

consumer.concurrency=3은 각 프로세스의 실행 스레드 수다. 모든 프로세스가 공유하는 coupon.admission.capacity=3은 DB 발급 진입 수의 합계다. polling 간격은 coupon.consumer.poll-delay-ms=200, 미완료 회수 기준은 coupon.consumer.claim-idle-ms=30000이다.

## 상태·닫기·재개

```bash
COUPON_API_BASE=http://127.0.0.1:8080
COUPON_ADMISSION_URL="$COUPON_API_BASE/api-admin/v1/local/coupon-admission"
curl -i -u "${COUPON_OPERATIONS_USERNAME}:${COUPON_OPERATIONS_PASSWORD}" "$COUPON_ADMISSION_URL"
curl -i -u "${COUPON_OPERATIONS_USERNAME}:${COUPON_OPERATIONS_PASSWORD}" \
  -X POST "$COUPON_ADMISSION_URL/close"
```

close는 공유 상태를 닫으며 서버를 종료하지 않는다. 새 예약·새 발급 진입은 중단하고, 진행 중 TX·보유 목록·발급 완료 조회는 유지한다. 상태의 accepting·activeTokens·epoch·reason을 확인한다.

모든 consumer의 기존 DB 작업 종료·커밋/롤백 결과를 확인한 뒤 재개한다. 토큰이 없다는 사실만으로 DB 종료를 증명할 수 없다. 최초 초기화도 같은 경로를 사용한다.

```bash
curl -i -u "${COUPON_OPERATIONS_USERNAME}:${COUPON_OPERATIONS_PASSWORD}" \
  -X POST "$COUPON_ADMISSION_URL/recover" \
  -H 'Content-Type: application/json' \
  --data '{"confirmed":true,"expectedTokens":[]}'
```

잔여 토큰이 있다면 상태에서 읽은 전체 값을 expectedTokens에 그대로 지정한다. 종료를 확인한 실행의 토큰만 정리할 수 있다. 목록이 실제 상태와 다르거나 같은 프로세스에 실행 중 작업이 있으면 409다. confirmed 누락·false는 400이다. Redis 상태 유실·응답 실패는 503이며 닫힘을 유지한다.

초기화된 캠페인의 Redis가 사라졌다면 DB 발급 목록으로 빈 재고를 재생성하지 않는다. 미발급 예약은 DB 목록만으로 복원할 수 없으므로 검증된 Redis 백업 복원이 필요하다.

운영 API는 local 프로필·명시적 활성화·별도 Basic 인증에 한정한다. 모든 노드의 종료 확인과 안전한 복원은 운영자의 책임이며 이 API가 자동 증명하지 않는다.

## 예약과 본인 결과

```bash
: "${COUPON_CAMPAIGN_ID:?준비 로그의 캠페인 ID를 지정하세요}"
COUPON_LOGIN_ID=couponuser
curl -i -X POST "$COUPON_API_BASE/api/v1/coupon-campaigns/$COUPON_CAMPAIGN_ID/issues" \
  -H "X-USER-ID: $COUPON_LOGIN_ID"
curl -i "$COUPON_API_BASE/api/v1/coupon-campaigns/$COUPON_CAMPAIGN_ID/issue" \
  -H "X-USER-ID: $COUPON_LOGIN_ID"
curl -i "$COUPON_API_BASE/api/v1/coupons" -H "X-USER-ID: $COUPON_LOGIN_ID"
```

| 상황 | 응답 |
| --- | --- |
| 새 예약 | 202, campaignId·PENDING·streamId |
| 같은 미완료 예약 | 200, 같은 streamId |
| 발급 완료 | 200, 기존 couponId |
| 실행 3자리 사용 중 | 예약은 유지, consumer가 이후 처리 |
| 최대 수량 모두 예약 | 409 COUPON_RESERVATIONS_FULL |
| DB 최종 품절 | 409 COUPON_SOLD_OUT, 확인 후 로컬 캐시 사용 |
| 신규 수용 닫힘·Redis 상태 불명 | 503 |

202는 발급 완료가 아니다. 캠페인·본인 결과의 ISSUED를 확인한다. Redis 장애 중 미완료 예약 조회에는 503이 발생할 수 있지만 발급 완료·보유 쿠폰은 DB에서 확인할 수 있다.

## Redis 읽기 전용 확인

```bash
redis-cli -h 127.0.0.1 -p 6379 HGETALL coupon:stream:control:state
redis-cli -h 127.0.0.1 -p 6379 SMEMBERS coupon:stream:control:active
redis-cli -h 127.0.0.1 -p 6379 GET "coupon:reservations:{$COUPON_CAMPAIGN_ID}:quota"
redis-cli -h 127.0.0.1 -p 6379 SCARD "coupon:reservations:{$COUPON_CAMPAIGN_ID}:members"
redis-cli -h 127.0.0.1 -p 6379 HLEN "coupon:reservations:{$COUPON_CAMPAIGN_ID}:receipts"
redis-cli -h 127.0.0.1 -p 6379 XLEN "coupon:reservations:{$COUPON_CAMPAIGN_ID}:requests"
redis-cli -h 127.0.0.1 -p 6379 XPENDING "coupon:reservations:{$COUPON_CAMPAIGN_ID}:requests" coupon-issuers
```

ACK 이후에도 members·receipts·Stream 원본은 보존한다. Pending만 0으로 줄어든다. 키를 수동 삭제하거나 TTL을 붙여 재고·실행 자리를 회수하지 않는다.
