package coupon.jpa

import com.loopers.coupon.domain.CouponAlreadyIssuedException
import com.loopers.coupon.domain.CouponBusinessException
import com.loopers.coupon.domain.CouponFailure
import coupon.jpa.support.ReservedCouponDatabaseFixture
import coupon.jpa.support.reservedCouponRace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

class CouponReservedIssuingIntegrationTest {
    @Test
    fun `발급된 쿠폰의 만료 시각은 발급 시각으로부터 30일 뒤다`() {
        ReservedCouponDatabaseFixture().use { fixture ->
            val issued = fixture.issuer.issue(fixture.campaign.id, 42).coupon
            val saved = fixture.coupons.findById(issued.couponId).orElseThrow()

            assertThat(saved.expiresAt).isEqualTo(saved.issuedAt.plusSeconds(30L * 24 * 60 * 60))
            assertThat(fixture.query.owned(42).single().expiresAt).isEqualTo(saved.expiresAt)
        }
    }

    @Test
    fun `총량 5에 서로 다른 8명이 경쟁하면 5장만 발급한다`() {
        ReservedCouponDatabaseFixture(quota = 5).use { fixture ->
            val outcomes = reservedCouponRace(8) { user -> fixture.issuer.issue(fixture.campaign.id, user) }

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(5)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull() })
                .hasSize(3)
                .allMatch { it is CouponBusinessException && it.failure == CouponFailure.SOLD_OUT }
            assertThat(fixture.coupons.count()).isEqualTo(5)
            assertThat(fixture.issuedCount()).isEqualTo(5)
        }
    }

    @Test
    fun `같은 사용자에게 8개 작업이 재전달돼도 발급 효과는 한 번이다`() {
        ReservedCouponDatabaseFixture(quota = 1).use { fixture ->
            val outcomes = reservedCouponRace(8) { fixture.issuer.issue(fixture.campaign.id, 42) }

            assertThat(outcomes).allMatch { it.isSuccess }
            val results = outcomes.map { it.getOrThrow() }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results.map { it.coupon.couponId }.distinct()).hasSize(1)
            assertThat(fixture.coupons.count()).isEqualTo(1)
            assertThat(fixture.issuedCount()).isEqualTo(1)
        }
    }

    @Test
    fun `쿠폰 저장이 실패하면 수량 증가도 함께 롤백한다`() {
        ReservedCouponDatabaseFixture(quota = 1).use { fixture ->
            fixture.failCouponInserts()

            val error = assertThrows<RuntimeException> { fixture.issuer.issue(fixture.campaign.id, 42) }

            assertThat(generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().toList())
                .anyMatch { it.errorCode == 1644 }
            assertThat(fixture.coupons.count()).isZero()
            assertThat(fixture.issuedCount()).isZero()
        }
    }

    @Test
    fun `예약 이후 행사를 닫아도 해당 사용자의 발급을 유지한다`() {
        ReservedCouponDatabaseFixture(quota = 1).use { fixture ->
            fixture.deactivate()

            val result = fixture.issuer.issue(fixture.campaign.id, 42)

            assertThat(result.created).isTrue()
            assertThat(result.campaignExhausted).isTrue()
            assertThat(fixture.query.owned(42)).containsExactly(result.coupon)
            assertThat(fixture.issuedCount()).isEqualTo(1)
        }
    }

    @Test
    fun `이미 발급된 작업은 같은 결과를 돌려주고 수량을 늘리지 않는다`() {
        ReservedCouponDatabaseFixture(quota = 1).use { fixture ->
            val first = fixture.issuer.issue(fixture.campaign.id, 42)

            val repeated = fixture.issuer.issue(fixture.campaign.id, 42)

            assertThat(repeated.created).isFalse()
            assertThat(repeated.coupon).isEqualTo(first.coupon)
            assertThat(fixture.coupons.count()).isEqualTo(1)
            assertThat(fixture.issuedCount()).isEqualTo(1)
        }
    }

    @Test
    fun `없는 캠페인은 발급하지 않는다`() {
        ReservedCouponDatabaseFixture().use { fixture ->
            val error = assertThrows<CouponBusinessException> { fixture.issuer.issue(fixture.campaign.id + 999, 42) }

            assertThat(error.failure).isEqualTo(CouponFailure.CAMPAIGN_NOT_FOUND)
            assertThat(fixture.coupons.count()).isZero()
        }
    }

    @Test
    fun `수량이 0이면 쿠폰을 만들지 않는다`() {
        ReservedCouponDatabaseFixture(quota = 0).use { fixture ->
            val error = assertThrows<CouponBusinessException> { fixture.issuer.issue(fixture.campaign.id, 42) }

            assertThat(error.failure).isEqualTo(CouponFailure.SOLD_OUT)
            assertThat(fixture.issuedCount()).isZero()
            assertThat(fixture.coupons.count()).isZero()
        }
    }

    @Test
    fun `다른 유일 제약 위반을 중복 발급으로 숨기지 않는다`() {
        ReservedCouponDatabaseFixture().use { fixture ->
            fixture.uniqueDiscountAmount()
            fixture.issuer.issue(fixture.campaign.id, 1)

            val error = assertThrows<DataIntegrityViolationException> { fixture.issuer.issue(fixture.campaign.id, 2) }

            assertThat(error).isNotInstanceOf(CouponAlreadyIssuedException::class.java)
            assertThat(fixture.issuedCount()).isEqualTo(1)
            assertThat(fixture.coupons.count()).isEqualTo(1)
        }
    }
}
