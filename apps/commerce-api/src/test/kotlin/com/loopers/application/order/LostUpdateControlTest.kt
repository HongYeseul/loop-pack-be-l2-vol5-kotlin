package com.loopers.application.order

import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.support.ConcurrentRunner
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 갱신 유실을 보이는 음성 대조군, 제품 구현이 아님
 * 두 트랜잭션이 같은 재고를 읽은 뒤 각자 읽은 값에서 계산한 상수를 조건 없이 저장하면 한 번의 차감이 사라짐
 * 읽기 뒤 장벽은 이 대조군 안에만 있고 실제 서비스에는 없음
 */
@SpringBootTest
class LostUpdateControlTest @Autowired constructor(
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val dataSource: DataSource,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("두 트랜잭션이 재고 5 를 읽은 뒤 각자 4 를 저장하면, 둘 다 성공하지만 차감 하나가 사라진다.")
    @Test
    fun losesOneDecrement() {
        // arrange
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        val productId = productJpaRepository.save(ProductFixture.product(brandId = brandId, stock = INITIAL_STOCK)).productId
        val bothHaveRead = CyclicBarrier(2)

        // act
        val results = ConcurrentRunner.run(
            List(2) {
                {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        val read = connection.prepareStatement("select stock from product where id = ?").use { statement ->
                            statement.setLong(1, productId)
                            statement.executeQuery().use { rows ->
                                rows.next()
                                rows.getInt(1)
                            }
                        }
                        bothHaveRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        connection.prepareStatement("update product set stock = ? where id = ?").use { statement ->
                            statement.setInt(1, read - 1)
                            statement.setLong(2, productId)
                            statement.executeUpdate()
                        }
                        connection.commit()
                        read
                    }
                }
            },
        )

        // assert · 시간 초과나 SQL 오류는 갱신 유실로 세지 않음
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertThat(failures).isEmpty()
        val finalStock = productJpaRepository.findById(productId).get().stock
        assertAll(
            { assertThat(results.map { it.getOrThrow() }).containsExactly(INITIAL_STOCK, INITIAL_STOCK) },
            { assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1) },
            // 성공 2건이면 초기 재고 − 2 = 3 이어야 하는데 4
            { assertThat(INITIAL_STOCK - results.size).isNotEqualTo(finalStock) },
        )
    }

    companion object {
        private const val INITIAL_STOCK = 5
        private const val TIMEOUT_SECONDS = 10L
    }
}
