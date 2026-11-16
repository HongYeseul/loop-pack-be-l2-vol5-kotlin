package coupon.api

import com.loopers.config.CouponLocalFixture
import com.loopers.support.ConcurrentRunner
import com.loopers.testcontainers.RedisTestContainer
import com.loopers.user.infrastructure.UserJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.HttpStatus
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

@Testcontainers
class CouponApiIntegrationTest {
    @Test
    fun `접수 202와 발급 완료를 구분하고 목록에서 같은 쿠폰을 확인한다`() {
        CouponApiFixture(redis).use { f ->
            val response = f.issue()
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("PENDING")
            assertThat(f.coupons.count()).isZero()
            assertThat(f.processOne()).isTrue()
            val result = f.result().body!!["data"]
            assertThat(result["status"].asText()).isEqualTo("ISSUED")
            assertThat(f.owned().body!!["data"].first()["couponId"]).isEqualTo(result["couponId"])
        }
    }

    @Test
    fun `중복 접수는 같은 접수 ID를 반환하고 추가 발급하지 않는다`() {
        CouponApiFixture(redis).use { f ->
            val first = f.issue()
            val again = f.issue()
            assertThat(again.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(again.body!!["data"]["streamId"]).isEqualTo(first.body!!["data"]["streamId"])
            f.processOne()
            assertThat(f.issue().statusCode).isEqualTo(HttpStatus.OK)
            assertThat(f.coupons.count()).isEqualTo(1)
        }
    }

    @Test
    fun `같은 사용자의 동시 요청도 접수와 재고를 한 번만 차지한다`() {
        CouponApiFixture(redis, quota = 1).use { f ->
            val outcomes = ConcurrentRunner.run((1..8).map { { f.issue() } }).map { it.getOrThrow() }

            assertThat(outcomes.count { it.statusCode == HttpStatus.ACCEPTED }).isEqualTo(1)
            assertThat(outcomes.map { it.statusCode }).allMatch {
                it == HttpStatus.ACCEPTED || it == HttpStatus.OK
            }
            assertThat(f.processOne()).isTrue()
            assertThat(f.coupons.count()).isEqualTo(1)
            assertThat(f.issue().body!!["data"]["couponId"].asLong()).isEqualTo(f.coupons.findAll().single().id)
        }
    }

    @Test
    fun `Stream 유실 후 DB만으로 미발급 예약을 덮어쓰지 않고 재개를 거절한다`() {
        CouponApiFixture(redis, quota = 2, operations = true).use { f ->
            f.operate("/recover", mapOf("confirmed" to true))
            f.issue()
            f.processOne()
            f.issue("user2")
            f.operate("/close")
            val template = f.context.getBean("masterRedisTemplate") as RedisTemplate<*, *>
            template.connectionFactory!!.connection.use {
                it.keyCommands().del("${f.streamPrefix}:{${f.campaign.id}}:requests".toByteArray())
            }

            assertThat(f.operate("/recover", mapOf("confirmed" to true)).statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)

            assertThat(f.gate.status().accepting).isFalse()
            assertThat(f.issue("user3").statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(f.issue().statusCode).isEqualTo(HttpStatus.OK)
            assertThat(f.result("user2").statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(f.coupons.count()).isEqualTo(1)
        }
    }

    @Test
    fun `존재하지 않는 행사 결과 조회는 404이며 다른 행사의 수용을 닫지 않는다`() {
        CouponApiFixture(redis).use { f ->
            val missing = f.result(campaignId = f.campaign.id + 999999)

            assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(f.gate.status().accepting).isTrue()
            assertThat(f.issue().statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }
    }

    @Test
    fun `다른 사용자의 접수 결과는 조회할 수 없다`() {
        CouponApiFixture(redis).use { f ->
            f.issue()
            assertThat(f.result("user2").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `세 worker가 실행 중이어도 네 번째 요청은 접수되어 나중에 발급된다`() {
        CouponApiFixture(redis, held = true, workerEnabled = true).use { f ->
            val barrier = f.context.getBean(HeldAdmission::class.java)
            try {
                (1..3).forEach { assertThat(f.issue("user$it").statusCode).isEqualTo(HttpStatus.ACCEPTED) }
                check(barrier.entered.await(20, TimeUnit.SECONDS))
                assertThat(f.gate.status().activeTokens).hasSize(3)
                val fourth = f.issue("user4")
                assertThat(fourth.statusCode).isEqualTo(HttpStatus.ACCEPTED)
                assertThat(f.coupons.count()).isZero()
                barrier.proceed.release(3)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (f.coupons.count() < 4 && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                }
                assertThat(f.result("user4").body!!["data"]["status"].asText()).isEqualTo("ISSUED")
                assertThat(f.coupons.count()).isEqualTo(4)
            } finally {
                barrier.proceed.release(3)
            }
        }
    }

    @Test
    fun `Redis 장애는 새 접수를 막고 기존 쿠폰 결과는 유지한다`() {
        RedisTestContainer().use { owned ->
            owned.start()
            CouponApiFixture(owned).use { f ->
                f.issue()
                f.processOne()
                owned.stop()
                assertThat(f.issue("user2").statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                assertThat(f.owned().statusCode).isEqualTo(HttpStatus.OK)
                assertThat(f.issue().statusCode).isEqualTo(HttpStatus.OK)
            }
        }
    }

    @Test
    fun `운영자는 서버를 유지한 채 접수와 처리를 재개한다`() {
        CouponApiFixture(redis, operations = true).use { f ->
            assertThat(f.issue().statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(f.operate("/recover", mapOf("confirmed" to true)).statusCode).isEqualTo(HttpStatus.OK)
            assertThat(f.issue().statusCode).isEqualTo(HttpStatus.ACCEPTED)
            assertThat(f.processOne()).isTrue()
            assertThat(f.context.isActive).isTrue()
        }
    }

    @Test
    fun `닫힌 동안에도 접수 기록을 유지하고 재개 후 처리한다`() {
        CouponApiFixture(redis, operations = true).use { f ->
            f.operate("/recover", mapOf("confirmed" to true))
            f.issue()
            f.operate("/close")
            assertThat(f.processOne()).isFalse()
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("PENDING")
            f.operate("/recover", mapOf("confirmed" to true))
            assertThat(f.processOne()).isTrue()
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("ISSUED")
        }
    }

    @Test
    fun `쿠폰 저장 실패는 접수를 미완료로 남기고 다음 실행에서 재처리한다`() {
        CouponApiFixture(redis).use { f ->
            f.issue()
            f.failCouponInserts()
            assertThrows<RuntimeException> { f.processOne() }
            assertThat(f.coupons.count()).isZero()
            assertThat(f.campaigns.findById(f.campaign.id).orElseThrow().issuedCount).isZero()
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("PENDING")
            f.allowCouponInserts()
            assertThat(f.processOne()).isTrue()
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("ISSUED")
            assertThat(f.coupons.count()).isEqualTo(1)
        }
    }

    @Test
    fun `자리 반환이 실패해도 커밋된 쿠폰과 접수 완료 상태는 보존한다`() {
        RedisTestContainer().use { owned ->
            owned.start()
            CouponApiFixture(owned, releaseHook = true).use { f ->
                f.issue()
                f.context.getBean(ReleaseHookAdmission::class.java).beforeRelease = {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    owned.execInContainer("redis-cli", "CLIENT", "PAUSE", "10000", "WRITE")
                }
                try {
                    assertThat(f.processOne()).isTrue()
                    assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("ISSUED")
                    assertThat(f.coupons.count()).isEqualTo(1)
                    assertThrows<com.loopers.coupon.stream.domain.CouponStreamException> { f.gate.status() }
                } finally {
                    owned.execInContainer("redis-cli", "CLIENT", "UNPAUSE")
                }
                assertThat(f.gate.status().accepting).isFalse()
                assertThat(f.issue("user2").statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            }
        }
    }

    @Test
    fun `접수 응답 쓰기가 실패해도 재요청은 같은 Stream 접수를 돌려준다`() {
        CouponApiFixture(redis, responseLoss = true).use { f ->
            assertThat(f.issue(dropResponse = true).statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            val userId = f.context.getBean(UserJpaRepository::class.java).findByLoginIdValue("user1")!!.id
            val saved = f.stream.reservation(f.campaign.id, userId)

            val repeated = f.issue()

            assertThat(repeated.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(repeated.body!!["data"]["streamId"].asText()).isEqualTo(saved)
            assertThat(f.stream.status(f.campaign.id, f.group).entries).isEqualTo(1)
            f.processOne()
            assertThat(f.coupons.count()).isEqualTo(1)
        }
    }

    @Test
    fun `앱 재시작 후에도 같은 Redis 접수를 조회하고 재개하면 발급한다`() {
        CouponApiFixture(redis, operations = true).use { f ->
            f.operate("/recover", mapOf("confirmed" to true))
            val accepted = f.issue().body!!["data"]["streamId"].asText()
            f.operate("/close")
            f.restartServer()

            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("PENDING")
            assertThat(f.result().body!!["data"]["streamId"].asText()).isEqualTo(accepted)
            assertThat(f.operate("/recover", mapOf("confirmed" to true)).statusCode).isEqualTo(HttpStatus.OK)
            f.processOne()
            assertThat(f.result().body!!["data"]["status"].asText()).isEqualTo("ISSUED")
        }
    }

    @Test
    fun localPreparationReusesItsCampaignAndUser() {
        CouponApiFixture(redis, operations = true, localFixture = true).use { f ->
            val users = f.context.getBean(UserJpaRepository::class.java)
            val campaign = f.campaigns.findFirstByName("로컬 쿠폰 행사")!!
            val user = users.findByLoginIdValue("couponuser")!!
            f.context.getBean(CouponLocalFixture::class.java).run(DefaultApplicationArguments())
            assertThat(f.campaigns.findAll().filter { it.name == campaign.name }).hasSize(1)
            assertThat(users.findByLoginIdValue("couponuser")!!.id).isEqualTo(user.id)
        }
    }

    companion object {
        @Container @JvmStatic
        val redis = RedisTestContainer()
    }
}
