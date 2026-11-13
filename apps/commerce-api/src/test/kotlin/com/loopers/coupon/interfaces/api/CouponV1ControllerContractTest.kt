package com.loopers.coupon.interfaces.api

import com.loopers.config.jackson.JacksonConfig
import com.loopers.coupon.application.CouponFacade
import com.loopers.coupon.application.CouponInfo
import com.loopers.coupon.application.CouponReservationSubmission
import com.loopers.coupon.application.CouponReservationInfo
import com.loopers.coupon.domain.CouponRequestStatus
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.interfaces.api.WebMvcConfig
import com.loopers.user.domain.LoginId
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** Facade mock을 사용한 HTTP 계약 검증, DB 발급·수용과 보안 필터 검증은 별도 통합 테스트 담당 */
@WebMvcTest(CouponV1Controller::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebMvcConfig::class, JacksonConfig::class)
class CouponV1ControllerContractTest @Autowired constructor(private val mockMvc: MockMvc) {
    @MockkBean
    private lateinit var couponFacade: CouponFacade

    private val loginId = LoginId("buyer1")
    private val coupon = CouponInfo(10, 7, "가을 행사", "5,000원 할인권", 5000, Instant.parse("2026-11-11T00:00:00Z"))

    @Test
    fun newRequestReturns202WithPendingReceipt() {
        val request = CouponReservationInfo(7, CouponRequestStatus.PENDING, streamId = "1-0")
        every { couponFacade.issue(loginId, 7) } returns CouponReservationSubmission(reservation = request, accepted = true)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.streamId").value("1-0"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.campaignExhausted").doesNotExist())
    }

    @Test
    fun requestResultReturnsTheOwnedReceipt() {
        every { couponFacade.result(loginId, 7) } returns CouponReservationInfo(7, CouponRequestStatus.ISSUED, 10)
        mockMvc.perform(get("/api/v1/coupon-campaigns/7/issue").header("X-USER-ID", loginId.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("ISSUED"))
            .andExpect(jsonPath("$.data.couponId").value(10))
    }

    @Test
    fun existingIssueReturns200WithOriginalCouponId() {
        every { couponFacade.issue(loginId, 7) } returns CouponReservationSubmission(coupon = coupon)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.couponId").value(10))
            .andExpect(jsonPath("$.data.expiresAt").value("2026-12-11T00:00:00Z"))
    }

    @Test
    fun ownedCouponsPreserveApplicationOrder() {
        val newest = coupon.copy(couponId = 12, issuedAt = Instant.parse("2026-11-11T01:00:00Z"))
        every { couponFacade.owned(loginId) } returns listOf(newest, coupon)

        mockMvc.perform(get("/api/v1/coupons").header("X-USER-ID", loginId.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].couponId").value(12))
            .andExpect(jsonPath("$.data[1].couponId").value(10))

        verify(exactly = 1) { couponFacade.owned(loginId) }
    }

    @Test
    fun noOwnedCouponsReturnsEmptyList() {
        every { couponFacade.owned(loginId) } returns emptyList()

        mockMvc.perform(get("/api/v1/coupons").header("X-USER-ID", loginId.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isEmpty)
    }

    @Test
    fun issueWithoutUserHeaderReturns400() {
        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("USER_NOT_IDENTIFIED"))

        verify(exactly = 0) { couponFacade.issue(any(), any()) }
    }

    @Test
    fun ownedWithoutUserHeaderReturns400() {
        mockMvc.perform(get("/api/v1/coupons"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value("USER_NOT_IDENTIFIED"))

        verify(exactly = 0) { couponFacade.owned(any()) }
    }

    @Test
    fun fullReservationsReturn409() {
        every { couponFacade.issue(loginId, 7) } throws CoreException(ErrorType.COUPON_RESERVATIONS_FULL)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_RESERVATIONS_FULL"))
    }

    @Test
    fun unavailableAdmissionReturns503() {
        every { couponFacade.issue(loginId, 7) } throws CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_UNAVAILABLE"))
    }

    @Test
    fun missingCampaignReturns404() {
        every { couponFacade.issue(loginId, 7) } throws CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_CAMPAIGN_NOT_FOUND"))
    }

    @Test
    fun inactiveCampaignReturns409() {
        every { couponFacade.issue(loginId, 7) } throws CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_CAMPAIGN_INACTIVE"))
    }

    @Test
    fun soldOutCampaignReturns409() {
        every { couponFacade.issue(loginId, 7) } throws CoreException(ErrorType.COUPON_SOLD_OUT)

        mockMvc.perform(post("/api/v1/coupon-campaigns/7/issues").header("X-USER-ID", loginId.value))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_SOLD_OUT"))
    }
}
