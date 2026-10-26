package com.loopers.admin.domain

/** 역할별 권한은 코드에 고정, 런타임 DB 변경으로 권한이 늘어나는 위험 방지 */
enum class AdminRole(val permissions: Set<AdminPermission>) {
    /** 관리자·역할 관리가 가능한 유일한 역할 */
    SUPER_ADMIN(AdminPermission.entries.toSet()),

    /** 개인정보 접근 불가 */
    CATALOG_ADMIN(setOf(AdminPermission.CATALOG_READ, AdminPermission.CATALOG_WRITE)),

    /** 구매자 마스킹 해제 불가 */
    ORDER_ADMIN(
        setOf(
            AdminPermission.CATALOG_READ,
            AdminPermission.ORDER_READ,
            AdminPermission.CUSTOMER_READ_MASKED,
        ),
    ),

    /** 마스킹 해제 가능, 조회 기록 대상 */
    CS_ADMIN(
        setOf(
            AdminPermission.CATALOG_READ,
            AdminPermission.ORDER_READ,
            AdminPermission.CUSTOMER_READ_MASKED,
            AdminPermission.CUSTOMER_READ_UNMASKED,
        ),
    ),
}
