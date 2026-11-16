# 쿠폰 검증

## 일반 검사

```bash
./gradlew ktlintCheck :modules:coupon:check :apps:commerce-api:check :apps:commerce-streamer:check --continue
```

- 공유 쿠폰 모듈: JPA 발급 총량·중복·롤백, Redis 예약·Stream·실행 제한, 커밋과 ACK 순서
- commerce-api: HTTP 응답과 인증, 예약·발급 조회, 품절 캐시와 수동 재개
- commerce-streamer: consumer 설정과 애플리케이션 검사

쿠폰 만료 시각 추가 전 검사에서 일반 테스트 772건(API 665건·streamer 80건·공유 쿠폰 27건)과 전체 ktlint가 통과했다.
