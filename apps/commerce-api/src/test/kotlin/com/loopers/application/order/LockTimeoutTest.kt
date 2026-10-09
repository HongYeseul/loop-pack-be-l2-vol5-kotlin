package com.loopers.application.order

import com.loopers.application.product.ProductFacade
import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminRole
import com.loopers.domain.product.ProductRepository
import com.loopers.fixture.AdminUserFixture
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 다른 트랜잭션이 쥔 행 락을 기다리는 시간에 상한이 있는지 확인, MySQL 기본 50초면 테스트의 대기 제한에 걸려 실패 */
@SpringBootTest
class LockTimeoutTest @Autowired constructor(
    private val productFacade: ProductFacade,
    private val productRepository: ProductRepository,
    private val transactionManager: PlatformTransactionManager,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("다른 트랜잭션이 잠근 상품 행을 기다리면, 3초 안팎에 락 대기 시간 초과로 실패한다.")
    @Test
    fun givesUpWaitingForLockAfterAboutThreeSeconds() {
        // arrange
        adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "admin", roles = arrayOf(AdminRole.SUPER_ADMIN)))
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        val productId = productJpaRepository.save(ProductFixture.product(brandId = brandId)).productId
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val holder = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    productRepository.findAliveForUpdate(productId)
                    locked.countDown()
                    release.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)
                }
            }
            check(locked.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS)) { "락 선점 시간 초과" }

            // act
            val startedAt = System.nanoTime()
            val contender = executor.submit<Result<*>> { runCatching { productFacade.changeStock(AdminLoginId("admin"), productId, 10) } }
            val result = contender.get(CONTENDER_LIMIT_SECONDS, TimeUnit.SECONDS)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            // assert · 즉시 실패(락을 아예 안 기다림)와 오래 기다림을 모두 걸러냄
            val rootMessage = result.exceptionOrNull()?.let { error -> generateSequence(error) { it.cause }.last().message }
            release.countDown()
            holder.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS)
            assertThat(result.isFailure).isTrue()
            assertThat(rootMessage).contains("Lock wait timeout")
            assertThat(elapsedMillis).isBetween(2_500L, 6_000L)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val WAIT_LIMIT_SECONDS = 10L

        /** 기본 50초를 기다리는 상태라면 여기서 시간 초과로 실패 */
        private const val CONTENDER_LIMIT_SECONDS = 15L

        /** 기다리는 쪽의 제한보다 길어야 락을 쥔 쪽이 먼저 놓아서 성공하는 일이 없음 */
        private const val HOLD_LIMIT_SECONDS = 60L
    }
}
