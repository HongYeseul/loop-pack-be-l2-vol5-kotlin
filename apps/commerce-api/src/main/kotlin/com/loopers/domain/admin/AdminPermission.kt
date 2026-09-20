package com.loopers.domain.admin

/**
 * 관리자가 할 수 있는 일의 최소 단위 (P-43).
 *
 * 역할만으로는 부족하다는 것이 D-12 의 요구다 — 개인정보의 안전성 확보조치 기준은
 * **열람 · 입력 · 수정 · 삭제 · 다운로드 · 출력**까지 세분화하라고 한다.
 * 이번 범위에서 실제로 갈라야 하는 축은 **카탈로그 · 주문 · 개인정보** 셋이다.
 */
enum class AdminPermission {
    /** 브랜드·상품 조회 (A-1 · A-3 · A-6 · A-8) */
    CATALOG_READ,

    /** 브랜드·상품 생성·수정·삭제·재고·판매 상태 (A-2 · A-4 · A-5 · A-7 · A-9 ~ A-11 · A-16) */
    CATALOG_WRITE,

    /** 주문 조회 (A-12 · A-13) */
    ORDER_READ,

    /** 구매자 **마스킹** 조회 (A-14 · P-34) */
    CUSTOMER_READ_MASKED,

    /** 구매자 **마스킹 해제** 조회 (A-15 · P-35). 조회 기록이 따라붙는 유일한 권한이다 */
    CUSTOMER_READ_UNMASKED,

    /** 관리자 계정과 역할 관리 */
    ADMIN_MANAGE,
}
