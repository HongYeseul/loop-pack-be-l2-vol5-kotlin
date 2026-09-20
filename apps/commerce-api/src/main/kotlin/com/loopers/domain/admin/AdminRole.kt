package com.loopers.domain.admin

/**
 * 관리자 역할 (P-43 · D-16).
 *
 * **역할이 어떤 권한을 갖는지는 코드에 둔다.** 그 구성을 바꾸는 것은 정책 변경이라
 * 리뷰와 배포를 거치는 편이 맞다. 런타임에 DB 한 줄로 권한이 늘어나는 쪽이 더 위험하다.
 * D-12 가 요구하는 **3년 보관**의 대상은 "누가 어느 역할을 가졌나"이고, 그것은 테이블로 남는다
 * ([AdminRoleHistory]).
 *
 * 이 매핑이 D-12 의 **최소 권한 차등 부여**를 실제로 구현한 자리다 —
 * `CATALOG_ADMIN` 은 상품을 고칠 수 있지만 **개인정보를 볼 수 없다.**
 */
enum class AdminRole(val permissions: Set<AdminPermission>) {
    /** 전권. 관리자와 역할을 관리할 수 있는 유일한 역할이다. */
    SUPER_ADMIN(AdminPermission.entries.toSet()),

    /** 브랜드·상품·재고·판매 상태. 개인정보에는 닿지 않는다. */
    CATALOG_ADMIN(setOf(AdminPermission.CATALOG_READ, AdminPermission.CATALOG_WRITE)),

    /** 주문을 보고 구매자를 마스킹된 채로 본다. 해제는 못 한다. */
    ORDER_ADMIN(
        setOf(
            AdminPermission.CATALOG_READ,
            AdminPermission.ORDER_READ,
            AdminPermission.CUSTOMER_READ_MASKED,
        ),
    ),

    /** CS. 문의 응대를 위해 마스킹을 해제할 수 있다. 대신 조회가 기록된다 (P-35). */
    CS_ADMIN(
        setOf(
            AdminPermission.CATALOG_READ,
            AdminPermission.ORDER_READ,
            AdminPermission.CUSTOMER_READ_MASKED,
            AdminPermission.CUSTOMER_READ_UNMASKED,
        ),
    ),
}
