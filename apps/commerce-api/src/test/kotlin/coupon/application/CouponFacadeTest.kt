package coupon.application

import com.loopers.coupon.application.CouponFacade
import com.loopers.coupon.application.CouponQueryService
import com.loopers.coupon.application.CouponSoldOutCache
import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponReservation
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.User
import com.loopers.user.domain.UserService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CouponFacadeTest {
    private val users = mockk<UserService>()
    private val query = mockk<CouponQueryService>()
    private val campaigns = mockk<CouponCampaignRepository>()
    private val stream = mockk<CouponStream>()
    private val gate = mockk<CouponExecutionGate>(relaxed = true)
    private val cache = CouponSoldOutCache()
    private val facade = CouponFacade(users, query, campaigns, stream, gate, cache)
    private val loginId = LoginId("user1")

    @Test
    fun `Stream 등록 응답이 불명확하면 성공이나 품절로 바꾸지 않는다`() {
        prepare()
        every { stream.reserve(7, 1) } throws CouponStreamException(CouponStreamFailure.UNAVAILABLE)

        val error = assertThrows<CoreException> { facade.issue(loginId, 7) }

        assertThat(error.errorType).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE)
        assertThat(cache.contains(7)).isFalse()
    }

    @Test
    fun `새 예약은 Stream에 저장한다`() {
        prepare()
        every { stream.reserve(7, 1) } returns CouponReservation("10-0", true)

        val accepted = facade.issue(loginId, 7)

        assertThat(accepted.accepted).isTrue()
        assertThat(accepted.reservation!!.streamId).isEqualTo("10-0")
    }

    private fun prepare() {
        val user = mockk<User>()
        every { user.userId } returns 1
        every { users.getActiveOrThrow(loginId) } returns user
        every { query.existing(7, 1) } returns null
        every { stream.reservation(7, 1) } returns null
        every { campaigns.find(7) } returns CouponCampaign("행사", 100, "혜택", 5000)
        every { gate.status() } returns CouponGateStatus(true, emptySet(), "epoch", null)
    }
}
