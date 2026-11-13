package coupon

import coupon.support.CouponDatabaseFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CouponQueryServiceIntegrationTest {
    @Test
    fun ownedReturnsOnlyTheRequestedUsersCoupons() {
        CouponDatabaseFixture().use { fixture ->
            val mine = fixture.seedCoupon(42, Instant.parse("2026-11-11T00:00:00Z"))
            fixture.seedCoupon(99, Instant.parse("2026-11-11T00:00:00Z"))

            val coupons = fixture.query.owned(42)

            assertThat(coupons.map { it.couponId }).containsExactly(mine.id)
        }
    }

    @Test
    fun ownedOrdersByIssuedAtDescendingBeforeCouponId() {
        CouponDatabaseFixture().use { fixture ->
            val newer = fixture.seedCoupon(42, Instant.parse("2026-11-11T01:00:00Z"))
            val olderCampaign = fixture.anotherCampaign("이전 행사")
            val older = fixture.seedCoupon(42, Instant.parse("2026-11-11T00:00:00Z"), olderCampaign)

            val coupons = fixture.query.owned(42)

            assertThat(coupons.map { it.couponId }).containsExactly(newer.id, older.id)
        }
    }

    @Test
    fun ownedOrdersEqualIssuedAtByCouponIdDescending() {
        CouponDatabaseFixture().use { fixture ->
            val issuedAt = Instant.parse("2026-11-11T00:00:00Z")
            val first = fixture.seedCoupon(42, issuedAt)
            val secondCampaign = fixture.anotherCampaign("두 번째 행사")
            val second = fixture.seedCoupon(42, issuedAt, secondCampaign)
            val thirdCampaign = fixture.anotherCampaign("세 번째 행사")
            val third = fixture.seedCoupon(42, issuedAt, thirdCampaign)

            val coupons = fixture.query.owned(42)

            assertThat(coupons.map { it.couponId }).containsExactly(third.id, second.id, first.id)
        }
    }

    @Test
    fun existingDoesNotReturnAnotherUsersCoupon() {
        CouponDatabaseFixture().use { fixture ->
            fixture.seedCoupon(42, Instant.parse("2026-11-11T00:00:00Z"))

            val coupon = fixture.query.existing(fixture.campaign.id, 99)

            assertThat(coupon).isNull()
        }
    }
}
