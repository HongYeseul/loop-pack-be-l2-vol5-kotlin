package coupon.api

import com.loopers.coupon.application.CouponSoldOutCache
import com.loopers.testcontainers.RedisTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class CouponSoldOutCacheApiIntegrationTest {
    @Test
    fun `100명의 재고 예약은 consumer가 멈춰 있어도 수용되고 초과 예약은 거절된다`() {
        CouponApiFixture(redis, quota = 100, userCount = 101).use { f ->
            (1..100).forEach { assertThat(f.issue("user$it").statusCode).isEqualTo(HttpStatus.ACCEPTED) }
            assertThat(f.coupons.count()).isZero()
            assertThat(f.issue("user101").statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(f.context.getBean(CouponSoldOutCache::class.java).contains(f.campaign.id)).isFalse()
            assertThat(f.gate.status().accepting).isTrue()
        }
    }

    @Test
    fun `100장 발급을 확정한 뒤 Redis가 중단돼도 품절 캐시로 응답한다`() {
        RedisTestContainer().use { owned ->
            owned.start()
            CouponApiFixture(owned, userCount = 101).use { f ->
                (1..100).forEach { f.issue("user$it") }
                repeat(100) { assertThat(f.processOne()).isTrue() }
                owned.stop()
                val result = f.issue("user101")
                assertThat(result.statusCode).isEqualTo(HttpStatus.CONFLICT)
                assertThat(result.body!!["meta"]["errorCode"].asText()).isEqualTo("COUPON_SOLD_OUT")
                assertThat(f.coupons.count()).isEqualTo(100)
            }
        }
    }

    @Test
    fun `품절 캐시가 있어도 기존 발급자는 같은 쿠폰과 200을 받는다`() {
        CouponApiFixture(redis, quota = 1).use { f ->
            f.issue()
            f.processOne()
            val issued = f.result().body!!["data"]["couponId"]
            assertThat(f.issue("user2").statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(f.context.getBean(CouponSoldOutCache::class.java).contains(f.campaign.id)).isTrue()
            val repeated = f.issue()
            assertThat(repeated.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(repeated.body!!["data"]["couponId"]).isEqualTo(issued)
        }
    }

    @Test
    fun `품절이 아닌 저장 실패는 재고 예약과 접수를 보존한다`() {
        CouponApiFixture(redis, quota = 1).use { f ->
            f.issue()
            f.failCouponInserts()
            val error = assertThrows<RuntimeException> { f.processOne() }
            assertThat(generateSequence<Throwable>(error) { it.cause }.last().message).contains("coupon insert rejected")
            assertThat(f.context.getBean(CouponSoldOutCache::class.java).contains(f.campaign.id)).isFalse()
            val next = f.issue("user2")
            assertThat(next.body!!["meta"]["errorCode"].asText()).isEqualTo("COUPON_RESERVATIONS_FULL")
            assertThat(f.gate.status().activeTokens).isEmpty()
        }
    }

    companion object {
        @Container @JvmStatic
        val redis = RedisTestContainer()
    }
}
