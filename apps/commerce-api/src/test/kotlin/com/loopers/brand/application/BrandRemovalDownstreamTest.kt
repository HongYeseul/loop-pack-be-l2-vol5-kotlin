package com.loopers.brand.application

import com.loopers.like.application.ProductLikeFacade
import com.loopers.order.application.OrderCommand
import com.loopers.order.application.OrderFacade
import com.loopers.product.application.ProductFacade
import com.loopers.admin.domain.AdminLoginId
import com.loopers.admin.domain.AdminRole
import com.loopers.order.domain.OrderStatus
import com.loopers.point.domain.PointService
import com.loopers.point.domain.PointTransactionType
import com.loopers.product.domain.ProductStatus
import com.loopers.user.domain.LoginId
import com.loopers.fixture.AdminUserFixture
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.admin.infrastructure.AdminUserJpaRepository
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.like.infrastructure.ProductLikeJpaRepository
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.point.infrastructure.PointTransactionJpaRepository
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 브랜드를 지운 뒤 그 상품이 고객·관리자 경로에서 어떻게 다뤄지는지 확인
 * 상품을 직접 지우지 않고 BrandFacade.delete 를 거쳐, 준비 데이터를 커밋한 뒤 새로 읽어 판단
 */
@SpringBootTest
class BrandRemovalDownstreamTest @Autowired constructor(
    private val brandFacade: BrandFacade,
    private val orderFacade: OrderFacade,
    private val productLikeFacade: ProductLikeFacade,
    private val productFacade: ProductFacade,
    private val pointService: PointService,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val productLikeJpaRepository: ProductLikeJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val requester = AdminLoginId("admin")
    private val loginId = LoginId(UserFixture.DEFAULT_LOGIN_ID)
    private var userId: Long = 0L
    private var brandId: Long = 0L

    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "admin", roles = arrayOf(AdminRole.SUPER_ADMIN)))
        userId = userJpaRepository.save(UserFixture.user()).userId
        brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun productOf(brandId: Long, stock: Int = 5, price: Long = 3_500L): Long =
        productJpaRepository.save(ProductFixture.product(brandId = brandId, stock = stock, price = price)).productId

    private fun product(id: Long) = productJpaRepository.findById(id).get()

    @DisplayName("브랜드를 지운 뒤 주문을 확정할 때,")
    @Nested
    inner class ConfirmOrder {
        @DisplayName("지우기 전에 만든 DRAFT 는 거절되고, 주문 상태·재고·잔액·원장이 그대로다.")
        @Test
        fun rejectsDraftCreatedBeforeBrandRemoval() {
            // arrange
            val productId = productOf(brandId, stock = 5, price = 3_500L)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(
                loginId,
                OrderCommand(items = listOf(OrderCommand.Item(productId = productId, quantity = 2))),
            ).id
            brandFacade.delete(requester, brandId)

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
                { assertThat(orderJpaRepository.findById(orderId).get().status).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(product(productId).stock).isEqualTo(5) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(10_000L) },
                // 충전 원장 한 줄만 있고 사용 원장은 없음
                { assertThat(pointTransactionJpaRepository.findAll().map { it.type }).containsExactly(PointTransactionType.CHARGE) },
            )
        }
    }

    @DisplayName("브랜드를 지운 뒤 좋아요를 다룰 때,")
    @Nested
    inner class Like {
        @DisplayName("새 좋아요는 거절되고, 좋아요 행이 생기지 않는다.")
        @Test
        fun rejectsNewLike() {
            // arrange
            val productId = productOf(brandId)
            brandFacade.delete(requester, brandId)

            // act
            val exception = assertThrows<CoreException> { productLikeFacade.like(loginId, productId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
                { assertThat(productLikeJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("지우기 전에 누른 좋아요는 취소할 수 있고, 좋아요 행이 사라진다.")
        @Test
        fun allowsCancelingLikeMadeBeforeBrandRemoval() {
            // arrange
            val productId = productOf(brandId)
            productLikeFacade.like(loginId, productId)
            brandFacade.delete(requester, brandId)

            // act
            val result = productLikeFacade.unlike(loginId, productId)

            // assert
            assertAll(
                { assertThat(result.liked).isFalse() },
                { assertThat(productLikeJpaRepository.existsByUserIdAndProductId(userId, productId)).isFalse() },
                { assertThat(productLikeJpaRepository.count()).isZero() },
            )
        }
    }

    @DisplayName("브랜드를 지운 뒤 관리자가 상품을 고칠 때,")
    @Nested
    inner class AdminEdit {
        @DisplayName("이름·가격 변경은 거절되고, 값이 그대로다.")
        @Test
        fun rejectsNameAndPriceChange() {
            // arrange
            val productId = productOf(brandId, price = 3_500L)
            val before = product(productId)
            brandFacade.delete(requester, brandId)

            // act
            val exception = assertThrows<CoreException> {
                productFacade.changeNameAndPrice(requester, productId, name = "바꾼 이름", price = 9_900L)
            }

            // assert
            val after = product(productId)
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
                { assertThat(after.name).isEqualTo(before.name) },
                { assertThat(after.price).isEqualTo(3_500L) },
            )
        }

        @DisplayName("재고 변경은 거절되고, 재고가 그대로다.")
        @Test
        fun rejectsStockChange() {
            // arrange
            val productId = productOf(brandId, stock = 5)
            brandFacade.delete(requester, brandId)

            // act
            val exception = assertThrows<CoreException> { productFacade.changeStock(requester, productId, quantity = 99) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
                { assertThat(product(productId).stock).isEqualTo(5) },
            )
        }

        @DisplayName("판매 상태 변경은 거절되고, 상태가 그대로다.")
        @Test
        fun rejectsStatusChange() {
            // arrange
            val productId = productOf(brandId)
            brandFacade.delete(requester, brandId)

            // act
            val exception = assertThrows<CoreException> {
                productFacade.changeStatus(requester, productId, ProductStatus.SUSPENDED)
            }

            // assert · ON_SALE → SUSPENDED 는 전이 규칙상 허용, 거절 원인이 삭제임을 확인
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND) },
                { assertThat(product(productId).status).isEqualTo(ProductStatus.ON_SALE) },
            )
        }
    }
}
