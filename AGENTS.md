# AGENTS.md

AI 도구가 이 저장소에서 작업할 때 따르는 규칙.

## 작업 규칙

- 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
- 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## 패키지 의존 방향

`apps/commerce-api` 는 `com.loopers` 아래 계층별 패키지로 나뉜다. 화살표 반대 방향으로는 의존하지 않는다.

```
interfaces ──▶ application ──▶ domain ◀── infrastructure
```

| 계층 | 맡는 일 | 직접 의존하지 않는 것 |
| --- | --- | --- |
| `interfaces` | 고객·관리자 입력과 응답, HTTP 오류 매핑 | `infrastructure` |
| `application` | domain 의 행동·저장 약속을 이용한 유스케이스 | `interfaces`, `infrastructure` |
| `domain` | 상태·규칙, 필요한 repository 약속 | `interfaces`, `application`, `infrastructure` |
| `infrastructure` | repository 약속의 JPA 구현 | HTTP 응답 정책, 중복된 업무 규칙 |

이 규칙은 `ArchitectureTest` 로 검사한다. 검사가 실패하면 원인을 판단해서 고친다. 검사 대상을 좁히거나 규칙을 지우지 않는다.

## 검사 명령

```bash
# 구현 중 — 바꾼 기능만
./gradlew :apps:commerce-api:test --tests '*<TestName>'

# 기능을 마무리할 때
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check
```

pre-commit 훅은 저장소 전체 `ktlintCheck` 를 실행한다.
