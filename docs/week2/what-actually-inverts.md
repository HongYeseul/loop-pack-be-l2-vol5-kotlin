# 무엇이 뒤집히는가 — 의존 역전을 코드로 다시 읽었습니다

> **Writing Quest (W2)**
>
> - 설계 기록: [`commerce-basics-design.md`](./commerce-basics-design.md) · [`commerce-basics-plan.md`](./commerce-basics-plan.md)
> - 작성자 / 작성일: 홍예슬 / 2026-09-21

---

## 1. 시작

이번 주에는 코드보다 규칙이 먼저 있었습니다. `AGENTS.md` 에 적어둔 의존 방향입니다.

```
interfaces ──▶ application ──▶ domain ◀── infrastructure
```

화살표 셋 중 마지막 하나만 반대쪽을 봅니다.

규칙대로 쓰기는 했습니다. `ArchitectureTest` 도 통과했습니다.
그런데 **왜 저것만 반대인지** 설명해 보라고 하면 못 했습니다.
"인터페이스를 만들어서 주입하면 의존이 역전된다" 정도로 알고 있었는데,
그 문장이 정확히 무엇을 뒤집는다는 말인지 따져본 적이 없었습니다.

이번 주에 저장소 인터페이스를 열 개 썼습니다(템플릿의 `ExampleRepository` 는 뺀 숫자입니다).
그 김에 DIP 를 제대로 읽고, 제가 쓴 코드가 정말 그 원칙을 따르는지 한 줄씩 맞춰봤습니다.
이 글은 그 대조의 기록입니다.

---

## 2. 상황 · 예시

상품 저장소가 두 파일로 나뉘어 있습니다.

```kotlin
// domain/product/ProductRepository.kt
package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

interface ProductRepository {
    fun findAlive(id: Long): Product?
    fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product>
    fun existsAliveByBrandId(brandId: Long): Boolean
    // ...
}
```

```kotlin
// infrastructure/product/ProductRepositoryImpl.kt
package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductRepository   // ← 여기
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest

@Component
class ProductRepositoryImpl(
    private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
    override fun findAlive(id: Long): Product? = productJpaRepository.findByIdAndDeletedAtIsNull(id)
    // ...
}
```

제가 보려는 것은 메서드가 아니라 **import 문**입니다.

- `ProductRepositoryImpl`(infrastructure)은 `domain` 을 import 합니다.
- `ProductRepository`(domain)는 `infrastructure` 를 import 하지 않습니다. JPA 도 스프링 데이터도 없습니다.

그런데 실행할 때 부르는 방향은 정반대입니다.

| | 방향 |
| --- | --- |
| 실행할 때 부르는 순서 | `ProductFacade` → `ProductRepository` → (런타임에) `ProductRepositoryImpl` → JPA |
| 소스 코드가 의존하는 방향 | `ProductRepositoryImpl` → `ProductRepository` **(반대)** |

그리고 인터페이스의 시그니처가 이렇게 생겼습니다.

```kotlin
fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product>
```

`Pageable` 도 `Page` 도 없습니다. `PageResult` 는 제가 직접 만든, 값 네 개짜리 data class 이고,
왜 만들었는지는 그 파일에 적어두었습니다.

> 스프링의 `Page` 를 쓰지 않는 이유: `domain` 이 스프링 데이터 타입을 알게 되고,
> 그 타입이 `application` 을 지나 응답까지 새면 저장 기술이 계약에 섞인다.

---

## 3. 개념

### 원칙의 문장 두 개

로버트 마틴이 1996년에 정리한 원칙이고, 문장 두 개로 되어 있습니다. 제 말로 옮기면 이렇습니다.

1. 고수준(정책)이 저수준(세부)에 의존하지 않습니다. 둘 다 추상에 의존합니다.
2. 추상이 세부에 맞춰지지 않습니다. 세부가 추상에 맞춥니다.

이 저장소에서 고수준은 "삭제된 상품은 고객에게 보이지 않는다"(P-12) 같은 규칙이고,
저수준은 "MySQL 에 `deleted_at IS NULL` 을 붙여 SELECT 한다" 입니다.

### 뒤집히는 것은 호출이 아니라 의존입니다

2절의 표가 이 문장 하나입니다.
인터페이스가 없으면 부르는 방향과 의존하는 방향이 같습니다 — 부르려면 그 타입을 알아야 하니까요.
인터페이스를 사이에 두면 그 둘이 갈라지고, 한쪽만 반대가 됩니다.
**역전은 그 갈라짐을 말합니다.** 실행 순서는 하나도 안 바뀝니다.

`AGENTS.md` 의 화살표가 왜 하나만 반대인지가 여기서 풀렸습니다.
그 그림은 실행 순서 그림이 아니라 **import 그림**이었습니다.

### 의존성 주입(DI)과 같은 말이 아닙니다

제가 헷갈리고 있던 자리가 여기입니다. 이런 코드도 생성자 주입입니다.

```kotlin
@Component
class ProductFacade(
    private val productJpaRepository: ProductJpaRepository,   // infrastructure 의 타입
)
```

스프링이 객체를 넣어주고, 테스트에서 다른 걸 넣을 수도 있습니다. 그런데 방향은 하나도 안 뒤집혔습니다.
`application` 이 여전히 `infrastructure` 와 스프링 데이터를 압니다.

DI 는 **객체를 누가 넣어주느냐**이고, DIP 는 **소스가 무엇을 import 하느냐**입니다.
주입을 받는다고 역전이 되는 게 아니라, 받는 **타입이 어디 것이냐**가 정합니다.

### 인터페이스를 어디에 두느냐가 원칙의 절반입니다

인터페이스를 `infrastructure` 패키지에 두고 `domain` 이 그것을 import 하면 화살표는 그대로입니다.
이름만 인터페이스이고 의존은 여전히 domain → infrastructure 입니다.
**소유권이 고수준 쪽에 있어야** 방향이 바뀝니다.

이 저장소에서는 그 소유권을 규칙이 고정합니다.

```kotlin
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
    .check(classes)
```

인터페이스를 infrastructure 로 옮기는 순간 이 검사가 깨집니다.

같은 배치를 마틴 파울러는 Separated Interface 라고 부르고, 헥사고날 아키텍처에서는 포트와 어댑터라고 부릅니다.
이름은 여럿인데 배치는 같은 것이었습니다.

### 나머지 절반은 어휘입니다

위치가 맞아도 시그니처가 저수준 타입이면 방향은 샙니다.
`fun findAliveProducts(pageable: Pageable): Page<Product>` 였다면 인터페이스는 domain 에 있는데
`domain` 이 스프링 데이터를 import 하게 되고, 그 타입이 `application` 을 지나 응답 DTO 까지 따라갑니다.

그리고 **ArchUnit 은 이걸 못 막습니다.** 스프링은 `..infrastructure..` 패키지가 아니니까요.
규칙이 못 막는 자리를 시그니처가 막고 있었습니다.
지금 `application` 과 `interfaces` 에 `org.springframework.data` import 는 0개입니다.

메서드의 **목록**도 어휘입니다. `ProductRepository` 에는 `findById` 가 없습니다.

> **`findById` 를 두지 않는다** (DS-3). 모든 조회에 "삭제되지 않음" 이 따라붙는데(P-12)
> 조건을 이름이 들고 있으면 부르는 쪽이 **어느 질문인지 고르지 않을 수 없다**.

`OrderRepository` 도 같습니다. `findById` 대신 `findOwnedBy(orderId, userId)` 와
`findIgnoringOwner(orderId)` 가 있습니다. 관리자만 뒤엣것을 부르고, 이름이 "소유권을 안 본다"고 말해줍니다.
`PointTransactionRepository` 에는 `save` 하나뿐입니다 — 원장은 고치지 않는 것이라 고칠 길을 안 만들었습니다.

JPA 가 줄 수 있는 것의 목록이 아니라 **도메인이 묻는 질문의 목록**입니다.
이게 이번에 제일 실감난 부분이었습니다. 방향을 뒤집으면 이름 짓는 사람이 바뀝니다.

### 그래서 무엇을 얻었나

셋을 확인했습니다.

**하나, 결정을 되돌릴 수 있었습니다.**
설계에 "목록 조회는 QueryDSL 로" 라고 적어두고 3단계에서 실제로 넣었다가 뺐습니다.
바뀐 것은 `ProductRepositoryImpl` 의 한 메서드이고 `application`·`interfaces`·테스트는 그대로였습니다.
흔히 드는 이유인 "DB 를 갈아끼울 수 있다"는 저한테 아직 안 와닿는데, **잘못 고른 것을 되돌리는 일**은 이번 주에 실제로 일어났습니다.

**둘, 가짜로 갈아끼울 수 있었습니다.**
`ProductServiceTest` 는 스프링을 안 띄웁니다. 도메인 인터페이스를 구현한 `FakeProductRepository` 를
테스트 파일 안에 두고 `LinkedHashMap` 으로 답합니다.

**셋, 규칙이 저장 기술보다 오래 삽니다.**
"삭제된 상품은 안 보인다"는 `ProductRepository.findAlive` 라는 이름에 남아 있고,
`deleted_at IS NULL` 은 구현 한 줄에만 있습니다.

---

## 4. 판단 · 이유

### 검토한 대안

| | 인터페이스 위치 | 시그니처의 타입 | domain 이 알게 되는 것 |
| --- | --- | --- | --- |
| (a) | 없음 — `application` 이 `JpaRepository` 를 직접 | 스프링 | 스프링 데이터 · JPA |
| (b) | `domain` | 스프링(`Pageable`·`Page`) | 스프링 데이터 |
| (c) | `domain` | 직접 만든 값(`PageCriteria`·`PageResult`) | 없음 |
| (d) | `domain` + **엔티티도 분리** | 직접 만든 값 | 없음 (ORM 애너테이션까지 뺌) |

### 고른 것과 이유

**(c) 입니다.** 이유는 셋이었습니다.

1. **질문의 목록이 도메인 것이 됩니다.** (a) 로 가면 `findById(id)` 가 기본이 되고,
   "삭제 제외"(P-12)나 "내 주문인가"(P-02)를 부르는 쪽이 매번 기억해야 합니다. 한 번 빠뜨리면 조용히 샙니다.
2. **타입이 안 샙니다.** (b) 는 위치만 옮긴 것이고, 스프링 데이터 타입이 `application` 을 지나 응답까지 갑니다.
3. **규칙으로 고정됩니다.** `ArchitectureTest` 가 (a) 와 (b) 중 (a) 를 막고,
   (b) 는 못 막는 대신 시그니처에 스프링 타입을 안 쓰는 것으로 제가 막았습니다.

### 버린 것 — (d) 를 왜 안 했나

(d) 는 DIP 를 끝까지 미는 안입니다. 지금 `domain/product/Product` 는 `@Entity` 를 달고 있어서
**도메인 모델이 JPA 를 압니다.** 원칙대로면 영속 모델을 따로 만들고 둘을 매핑해야 합니다.

안 했습니다. **지금 그것이 푸는 문제가 없어서**입니다.
매핑 코드가 두 벌 생기고, 엔티티가 하나 늘 때마다 두 곳을 고쳐야 합니다.
같은 이유로 `domain/*Service` 의 `@Component`·`@Transactional` 도 그대로 뒀습니다.

원칙은 목적이 아니라 도구라고 보기로 했습니다.
`Page` 를 뺀 것과 `@Entity` 를 둔 것의 차이는 **지금 아픈 데가 있느냐**였습니다 —
`Page` 는 응답까지 따라오는 것이 눈에 보였고, `@Entity` 는 아직 아무 데도 안 아팠습니다.

### 이 선택이 비싸지는 조건

- **구현이 하나뿐이고 질문이 단순하면 순수 비용입니다.**
  `PointTransactionRepository` 는 `save` 하나짜리 인터페이스에 구현 클래스가 하나 붙어 있습니다.
  솔직히 말하면 이 자리가 값을 하는 이유는 교체 가능성이 아니라 **메서드를 안 둔 것**(고칠 길을 안 만든 것)이고,
  그건 DIP 때문이 아닙니다. 이런 자리는 세어 두는 편이 낫다고 봤습니다.
- **가짜가 계약을 어기면 테스트가 조용히 거짓말합니다.**
  정렬이 그 자리였습니다. 진짜는 SQL 이 정렬하고 가짜는 `sortedByDescending` 이 정렬하는데,
  둘이 다르면 단위 테스트는 초록인 채로 틀립니다.
  그래서 정렬 순서를 인터페이스 KDoc 에 **계약으로** 적고, 실제로 SQL 로 내려가는지는
  `ProductRepositoryIntegrationTest` 가 따로 봅니다. 인터페이스는 계약을 적을 자리를 주지만, 지키는지는 안 봐줍니다.
- **변환 코드가 구현마다 생깁니다.** `Page<Product>` 를 `PageResult<Product>` 로 바꾸는 `toPageResult` 같은 것들입니다.

---

## 5. 다음부터 하기로 한 것

저장소 인터페이스를 하나 만들 때 질문 넷을 하기로 했습니다.

**하나, 이 메서드 이름을 저장 기술을 모르는 사람이 읽어도 무엇을 묻는지 아는가?**
`findByIdAndDeletedAtIsNull` 은 컬럼 이름이고 `findAlive` 는 질문입니다.

**둘, 시그니처에 내가 고르지 않은 라이브러리 타입이 있는가?**
있으면 위치만 뒤집은 것입니다.

**셋, 이 인터페이스를 지금 가짜로 구현할 수 있는가?**
못 하면 무엇이 새고 있는지 그 자리에 답이 있습니다.

**넷, 구현이 하나뿐인데 인터페이스를 두려 한다면, 지금 그것이 푸는 문제를 한 문장으로 말할 수 있는가?**
못 하면 나중에 넣어도 늦지 않습니다. 이번에 `Page` 는 뺐고 `@Entity` 는 뒀습니다.

---

## 6. 새로 생긴 질문

**`domain` 이 `@Component` 와 `@Transactional` 을 아는 것은 어디까지 괜찮을까요.**
트랜잭션 경계는 `application` 에 있는데(`OrderFacade.confirm`) 도메인 서비스에도 애너테이션이 붙어 있습니다.
스프링 타입은 뺐으면서 스프링 애너테이션은 둔 셈이라, 기준을 한 문장으로 말하기가 아직 어렵습니다.

**이걸 누가 정해야 하는지**도 걸립니다.
`Page` 를 뺀 것은 응답에 영향이 가서 제가 정할 수 있었는데,
`@Entity` 나 `@Transactional` 을 어디까지 허용할지는 저 혼자 정하면 다음 사람이 다른 기준으로 씁니다.
팀 규약으로 올려야 하는 문제 같은데, 그러려면 **지금 아프지 않은 것을 규칙으로 만드는 비용**이 얼마인지부터 알아야 할 것 같습니다.

그리고 하나 더 — `ArchitectureTest` 에 "`domain` 은 `org.springframework.data` 를 import 하지 않는다"를
규칙으로 올릴지 아직 안 정했습니다. 지금은 제가 시그니처에서 지키고 있는데, 지키는 사람이 바뀌면 그냥 풀립니다.
