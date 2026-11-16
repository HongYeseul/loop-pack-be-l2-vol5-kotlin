package coupon.application

import com.loopers.coupon.application.CouponInfo
import com.loopers.coupon.domain.CouponRequestStatus
import com.loopers.coupon.application.CouponFacade
import com.loopers.coupon.application.CouponQueryService
import com.loopers.coupon.application.CouponSoldOutCache
import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponReservation
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.User
import com.loopers.user.domain.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CouponSoldOutAdmissionTest {
    private val users = mockk<UserService>()
    private val query = mockk<CouponQueryService>()
    private val campaigns = mockk<CouponCampaignRepository>()
    private val stream = mockk<CouponStream>(relaxed = true)
    private val gate = mockk<CouponExecutionGate>(relaxed = true)
    private val cache = CouponSoldOutCache()
    private val facade = CouponFacade(users, query, campaigns, stream, gate, cache)
    private val loginId = LoginId("user1")

    private fun prepare() {
        val user = mockk<User>()
        every { user.userId } returns 1
        every { users.getActiveOrThrow(loginId) } returns user
        every { query.existing(7, 1) } returns null
        every { stream.reservation(7, 1) } returns null
        every { campaigns.find(7) } returns CouponCampaign("행사", 100, "혜택", 5000)
        every { gate.status() } returns CouponGateStatus(true, emptySet(), "epoch", null)
    }

    @Test
    fun `기록된 품절이면 Redis를 호출하지 않는다`() {
        prepare()
        cache.remember(7)
        assertThat(assertThrows<CoreException> { facade.issue(loginId, 7) }.errorType).isEqualTo(ErrorType.COUPON_SOLD_OUT)
        verify(exactly = 0) { stream.reserve(any(), any()) }
    }

    @Test
    fun `품절이어도 기존 발급자는 같은 쿠폰을 받는다`() {
        prepare()
        cache.remember(7)
        val coupon = CouponInfo(10, 7, "행사", "혜택", 5000, Instant.EPOCH)
        every { query.existing(7, 1) } returns coupon
        assertThat(facade.issue(loginId, 7).coupon).isEqualTo(coupon)
        verify(exactly = 0) { stream.reserve(any(), any()) }
    }

    @Test
    fun `재고가 전부 예약된 것은 영구 품절로 캐시하지 않는다`() {
        prepare()
        every { campaigns.find(7) } returns CouponCampaign("행사", 100, "혜택", 5000)
        every { stream.reserve(7, 1) } throws CouponStreamException(CouponStreamFailure.FULL)
        assertThrows<CoreException> { facade.issue(loginId, 7) }
        assertThat(cache.contains(7)).isFalse()
    }

    @Test
    fun `중복 접수는 최초 접수 ID를 반환한다`() {
        prepare()
        every { stream.reservation(7, 1) } returns "20-0"
        val result = facade.issue(loginId, 7)
        assertThat(result.reservation!!.streamId).isEqualTo("20-0")
        assertThat(result.reservation!!.status).isEqualTo(CouponRequestStatus.PENDING)
        verify(exactly = 0) { stream.reserve(any(), any()) }
    }

    @Test
    fun `다른 캠페인의 품절은 발급 가능한 캠페인 접수에 영향을 주지 않는다`() {
        prepare()
        cache.remember(8)
        every { campaigns.find(7) } returns CouponCampaign("행사", 100, "혜택", 5000)
        every { stream.reserve(7, 1) } returns CouponReservation("20-0", true)

        assertThat(facade.issue(loginId, 7).accepted).isTrue()

        assertThat(cache.contains(7)).isFalse()
        verify(exactly = 1) { stream.reserve(7, 1) }
    }

    @Test
    fun `빈 캐시에서 DB의 실제 품절을 확인하면 기억하고 Redis를 생략한다`() {
        prepare()
        val campaign = mockk<CouponCampaign>()
        every { campaign.enabled } returns true
        every { campaign.issuedCount } returns 100
        every { campaign.totalQuota } returns 100
        every { campaigns.find(7) } returns campaign

        assertThat(assertThrows<CoreException> { facade.issue(loginId, 7) }.errorType).isEqualTo(ErrorType.COUPON_SOLD_OUT)

        assertThat(cache.contains(7)).isTrue()
        verify(exactly = 0) { stream.reserve(any(), any()) }
    }

    @Test
    fun `기존 결과 조회 중 마지막 발급이 완료돼도 재요청을 품절로 오판하지 않는다`() {
        prepare()
        every { query.existing(7, 1) } answers {
            cache.remember(7)
            null
        }
        every { campaigns.find(7) } returns CouponCampaign("행사", 100, "혜택", 5000)
        every { stream.reservation(7, 1) } returns "20-0"

        val result = facade.issue(loginId, 7)

        assertThat(result.reservation!!.streamId).isEqualTo("20-0")
    }
}
