package com.loopers.support.error

import org.springframework.http.HttpStatus

/**
 * 실패의 종류를 나타내는 식별자.
 *
 * 범용 에러는 `code` 가 HTTP 상태 이름이라 상태 코드 이상의 정보가 없다(1주차 관찰).
 * 그래서 도메인 실패는 **상태 코드와 분리된 식별자**를 따로 둔다 — 설계 DS-8.
 * `message` 는 요청자가 **다음에 무엇을 할지** 알 수 있게 쓴다.
 */
enum class ErrorType(val status: HttpStatus, val code: String, val message: String) {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase, "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "이미 존재하는 리소스입니다."),

    /** 식별 · P-01 */
    USER_NOT_IDENTIFIED(HttpStatus.BAD_REQUEST, "USER_NOT_IDENTIFIED", "X-USER-ID 헤더가 없거나 형식이 올바르지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "존재하지 않는 사용자입니다."),

    /** 브랜드 · 상품 · P-04 · P-11 */
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND", "존재하지 않는 브랜드입니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "존재하지 않는 상품입니다."),
    BRAND_HAS_PRODUCTS(HttpStatus.CONFLICT, "BRAND_HAS_PRODUCTS", "연결된 상품이 남아 있어 브랜드를 삭제할 수 없습니다. 상품을 먼저 삭제해주세요."),

    /** 상품 판매 상태 · P-38 */
    PRODUCT_NOT_PURCHASABLE(HttpStatus.CONFLICT, "PRODUCT_NOT_PURCHASABLE", "판매중지되었거나 단종된 상품입니다. 다른 상품을 골라주세요."),

    /** 목록 조회 입력 · P-08 */
    INVALID_SORT(HttpStatus.BAD_REQUEST, "INVALID_SORT", "지원하지 않는 정렬 값입니다."),
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 또는 페이지 크기가 허용 범위를 벗어났습니다."),

    /** 포인트 · P-19 · P-20 */
    CHARGE_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "CHARGE_AMOUNT_INVALID", "충전액이 허용 범위를 벗어났습니다."),
    BALANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED", "충전 결과가 잔액 상한을 넘습니다. 더 적은 금액으로 충전해주세요."),

    /** 주문 생성 · P-24 · P-25 */
    DUPLICATE_ORDER_ITEM(HttpStatus.BAD_REQUEST, "DUPLICATE_ORDER_ITEM", "같은 상품이 여러 품목으로 들어왔습니다. 품목을 합쳐 다시 보내주세요."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "수량은 1 이상이어야 합니다."),

    /** 주문 확정 · P-27 */
    OUT_OF_STOCK(HttpStatus.CONFLICT, "OUT_OF_STOCK", "재고가 부족합니다. 수량을 줄여주세요."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", "잔액이 부족합니다. 충전 후 다시 확정해주세요."),

    /** 주문 상태 · P-02 · P-29 · P-32 */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "존재하지 않는 주문입니다."),
    ORDER_NOT_DRAFT(HttpStatus.CONFLICT, "ORDER_NOT_DRAFT", "확정 전(DRAFT) 주문이 아닙니다. 주문 상태를 다시 확인해주세요."),
    ORDER_EXPIRED(HttpStatus.CONFLICT, "ORDER_EXPIRED", "만료된 주문입니다. 다시 주문해주세요."),
}
