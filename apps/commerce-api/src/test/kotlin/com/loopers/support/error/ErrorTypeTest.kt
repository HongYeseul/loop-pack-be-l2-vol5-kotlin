package com.loopers.support.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.http.HttpStatus

/**
 * 설계 DS-8 의 오류 식별자 표를 코드에 고정한다.
 *
 * 이 테스트가 보는 것은 "식별자가 있다" 가 아니라 **"코드 문자열이 상태 코드와 분리되어 있다"** 이다.
 * 1주차 관찰의 결론이 그것이었고, 그 성질이 깨지면 요청자가 실패 종류를 구분할 수 없게 된다.
 */
class ErrorTypeTest {
    @DisplayName("도메인 식별자는,")
    @Nested
    inner class DomainIdentifiers {
        /** 설계 DS-8 표 · 식별자 → HTTP 상태 */
        private val designedIdentifiers = mapOf(
            ErrorType.USER_NOT_IDENTIFIED to HttpStatus.BAD_REQUEST,
            ErrorType.USER_NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorType.USER_DEACTIVATED to HttpStatus.FORBIDDEN,
            ErrorType.USER_BLOCKED to HttpStatus.FORBIDDEN,
            ErrorType.USER_WITHDRAWN to HttpStatus.FORBIDDEN,
            ErrorType.ADMIN_NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorType.ADMIN_PERMISSION_DENIED to HttpStatus.FORBIDDEN,
            ErrorType.ADMIN_SELF_ROLE_CHANGE to HttpStatus.FORBIDDEN,
            ErrorType.BRAND_NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorType.PRODUCT_NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorType.BRAND_HAS_PRODUCTS to HttpStatus.CONFLICT,
            ErrorType.PRODUCT_NOT_PURCHASABLE to HttpStatus.CONFLICT,
            ErrorType.INVALID_SORT to HttpStatus.BAD_REQUEST,
            ErrorType.INVALID_PAGE to HttpStatus.BAD_REQUEST,
            ErrorType.CHARGE_AMOUNT_INVALID to HttpStatus.BAD_REQUEST,
            ErrorType.BALANCE_LIMIT_EXCEEDED to HttpStatus.CONFLICT,
            ErrorType.DUPLICATE_ORDER_ITEM to HttpStatus.BAD_REQUEST,
            ErrorType.INVALID_QUANTITY to HttpStatus.BAD_REQUEST,
            ErrorType.OUT_OF_STOCK to HttpStatus.CONFLICT,
            ErrorType.INSUFFICIENT_BALANCE to HttpStatus.CONFLICT,
            ErrorType.ORDER_NOT_FOUND to HttpStatus.NOT_FOUND,
            ErrorType.ORDER_NOT_DRAFT to HttpStatus.CONFLICT,
            ErrorType.ORDER_EXPIRED to HttpStatus.CONFLICT,
        )

        @DisplayName("설계가 정한 HTTP 상태로 응답한다.")
        @Test
        fun mapsToDesignedHttpStatus() {
            assertAll(
                designedIdentifiers.map { (errorType, status) ->
                    { assertThat(errorType.status).describedAs(errorType.name).isEqualTo(status) }
                },
            )
        }

        @DisplayName("코드 문자열이 HTTP 상태 이름이 아니라 식별자 이름이다. 그래서 같은 상태의 실패끼리 구분된다.")
        @Test
        fun hasCodeIndependentOfHttpStatus() {
            assertAll(
                designedIdentifiers.keys.map { errorType ->
                    {
                        assertAll(
                            { assertThat(errorType.code).describedAs(errorType.name).isEqualTo(errorType.name) },
                            { assertThat(errorType.code).describedAs(errorType.name).isNotEqualTo(errorType.status.reasonPhrase) },
                        )
                    }
                },
            )
        }

        @DisplayName("요청자에게 보일 메시지를 비워두지 않는다.")
        @Test
        fun hasMessage() {
            assertAll(
                designedIdentifiers.keys.map { errorType ->
                    { assertThat(errorType.message).describedAs(errorType.name).isNotBlank() }
                },
            )
        }
    }

    @DisplayName("같은 HTTP 상태를 쓰는 식별자가 여럿이어도, 코드 문자열은 서로 겹치지 않는다.")
    @Test
    fun hasUniqueCodeAcrossEntries() {
        // arrange
        val codes = ErrorType.entries.map { it.code }

        // assert
        assertThat(codes).doesNotHaveDuplicates()
    }
}
