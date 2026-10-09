package com.loopers.application.brand

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminRole
import com.loopers.domain.brand.BrandService
import com.loopers.application.order.OrderFacade
import com.loopers.domain.order.OrderService
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.point.PointTransactionType
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.user.LoginId
import com.loopers.fixture.AdminUserFixture
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.order.OrderItemJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.point.PointJpaRepository
import com.loopers.infrastructure.point.PointTransactionJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import io.mockk.verify
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
import java.time.ZonedDateTime

/**
 * 브랜드와 연결 상품의 삭제가 한 덩어리로 성공하거나 실패하는지 확인
 * 준비 데이터를 커밋해 두고, 트랜잭션이 끝난 뒤 새로 읽어 DB 에 남은 상태로 판단
 */
@SpringBootTest
class BrandRemovalTransactionTest @Autowired constructor(
    private val brandFacade: BrandFacade,
    private val orderFacade: OrderFacade,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val orderItemJpaRepository: OrderItemJpaRepository,
    private val pointJpaRepository: PointJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @SpykBean
    private lateinit var brandService: BrandService

    private val requester = AdminLoginId("admin")
    private var brandId: Long = 0L
    private var otherBrandId: Long = 0L

    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "admin", roles = arrayOf(AdminRole.SUPER_ADMIN)))
        brandId = brandJpaRepository.save(BrandFixture.brand(name = "지울 브랜드")).brandId
        otherBrandId = brandJpaRepository.save(BrandFixture.brand(name = "다른 브랜드")).brandId
    }

    /** @Nested 에서는 스파이 설정이 다음 테스트로 새므로 원래 동작으로 직접 복원 */
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        every { brandService.delete(any()) } answers { callOriginal() }
    }

    private fun productOf(brandId: Long, stock: Int = 5, status: ProductStatus = ProductStatus.ON_SALE): Long =
        productJpaRepository.save(ProductFixture.product(brandId = brandId, stock = stock, status = status)).productId

    private fun product(id: Long) = productJpaRepository.findById(id).get()

    private fun brand(id: Long) = brandJpaRepository.findById(id).get()

    private fun draftOrderOn(productId: Long): Long {
        val userId = userJpaRepository.save(UserFixture.user()).userId
        return orderService.create(
            userId = userId,
            lines = listOf(OrderFixture.line(productId = productId)),
            now = OrderFixture.BASE_TIME,
        ).orderId
    }

    /** 조회로 보이는 결제 결과 전부, 지우기 전후 비교용 */
    private data class PaidOrderView(
        val status: OrderStatus,
        val totalAmount: Long,
        val paidAmount: Long?,
        val confirmedAt: ZonedDateTime?,
        val items: List<Triple<Long, Int, Long>>,
        val usedLedger: List<Triple<Long?, Long, Long>>,
        val balance: Long,
    )

    private class PaidOrder(val orderId: Long, val userId: Long)

    /** 포인트로 결제까지 끝난 주문, 이후 상품 가격을 바꿔 현재 가격과 단가가 달라지게 함 */
    private fun paidOrderOn(productId: Long): PaidOrder {
        val user = userJpaRepository.save(UserFixture.user(loginId = "buyer1"))
        pointService.charge(user.userId, 20_000L)
        val orderId = orderService.create(
            userId = user.userId,
            lines = listOf(OrderFixture.line(productId = productId, quantity = 2, unitPrice = ProductFixture.DEFAULT_PRICE)),
            now = ZonedDateTime.now(),
        ).orderId
        orderFacade.confirm(LoginId("buyer1"), orderId)
        productJpaRepository.findById(productId).get().also {
            it.changeNameAndPrice(it.name, ProductFixture.DEFAULT_PRICE + 6_400L)
            productJpaRepository.save(it)
        }
        return PaidOrder(orderId, user.userId)
    }

    private fun viewOf(order: PaidOrder): PaidOrderView {
        val saved = orderJpaRepository.findById(order.orderId).get()
        return PaidOrderView(
            status = saved.status,
            totalAmount = saved.totalAmount,
            paidAmount = saved.paidAmount,
            confirmedAt = saved.confirmedAt,
            items = orderItemJpaRepository.findAllByOrderIdOrderByIdAsc(order.orderId).map { Triple(it.productId, it.quantity, it.unitPrice) },
            usedLedger = pointTransactionJpaRepository.findAll()
                .filter { it.userId == order.userId && it.type == PointTransactionType.USE }
                .map { Triple(it.orderId, it.amount, it.balanceAfter) },
            balance = pointJpaRepository.findByUserId(order.userId)!!.balance,
        )
    }

    @DisplayName("브랜드를 지우면,")
    @Nested
    inner class WhenSucceeds {
        @DisplayName("연결된 살아 있는 상품이 함께 지워진다. 재고 0·판매중지·단종도 포함한다.")
        @Test
        fun deletesBrandWithItsAliveProducts() {
            // arrange
            val productIds = listOf(
                productOf(brandId),
                productOf(brandId, stock = 0),
                productOf(brandId, status = ProductStatus.SUSPENDED),
                productOf(brandId, status = ProductStatus.DISCONTINUED),
            )

            // act
            brandFacade.delete(requester, brandId)

            // assert
            assertAll(
                { assertThat(brand(brandId).deletedAt).isNotNull() },
                { productIds.forEach { assertThat(product(it).deletedAt).isNotNull() } },
            )
        }

        @DisplayName("삭제 시각과 함께 수정 시각이 갱신된다. 벌크 UPDATE 는 엔티티 콜백을 거치지 않는다.")
        @Test
        fun refreshesUpdatedAt() {
            // arrange
            val productId = productOf(brandId)
            val before = product(productId).updatedAt

            // act
            brandFacade.delete(requester, brandId)

            // assert
            assertThat(product(productId).updatedAt).isAfter(before)
        }

        @DisplayName("다른 브랜드의 상품은 그대로다.")
        @Test
        fun keepsOtherBrandsProduct() {
            // arrange
            val otherProductId = productOf(otherBrandId)

            // act
            brandFacade.delete(requester, brandId)

            // assert
            assertAll(
                { assertThat(brand(otherBrandId).deletedAt).isNull() },
                { assertThat(product(otherProductId).deletedAt).isNull() },
            )
        }

        @DisplayName("이미 지워진 상품의 삭제 시각은 덮어쓰지 않는다.")
        @Test
        fun keepsDeletedAtOfAlreadyDeletedProduct() {
            // arrange
            val deletedId = productJpaRepository.save(ProductFixture.deletedProduct(brandId = brandId)).productId
            val before = product(deletedId).deletedAt

            // act
            brandFacade.delete(requester, brandId)

            // assert
            assertThat(product(deletedId).deletedAt).isEqualTo(before)
        }

        @DisplayName("결제까지 끝난 과거 주문의 품목·단가·총액·결제 결과가 그대로 조회된다. 현재 상품 가격으로 다시 계산하지 않는다.")
        @Test
        fun keepsPaidOrderUnchanged() {
            // arrange
            val order = paidOrderOn(productOf(brandId))
            val before = viewOf(order)

            // act
            brandFacade.delete(requester, brandId)

            // assert
            assertAll(
                { assertThat(viewOf(order)).isEqualTo(before) },
                { assertThat(before.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(before.items.single().third).isEqualTo(ProductFixture.DEFAULT_PRICE) },
                { assertThat(before.paidAmount).isEqualTo(2 * ProductFixture.DEFAULT_PRICE) },
            )
        }

        @DisplayName("지워진 상품을 담은 과거 주문은 그대로다.")
        @Test
        fun keepsPastOrders() {
            // arrange
            val orderId = draftOrderOn(productOf(brandId))

            // act
            brandFacade.delete(requester, brandId)

            // assert
            val orders = orderJpaRepository.findAll()
            assertAll(
                { assertThat(orders).hasSize(1) },
                { assertThat(orders.first().id).isEqualTo(orderId) },
                { assertThat(orders.first().status).isEqualTo(OrderStatus.DRAFT) },
            )
        }
    }

    @DisplayName("브랜드 삭제가 상품 삭제 뒤에 실패하면,")
    @Nested
    inner class WhenFailsInTheMiddle {
        @DisplayName("브랜드와 상품 모두 처음 상태로 돌아간다. 이미 나간 상품 UPDATE 도 취소된다.")
        @Test
        fun rollsBackProductsToo() {
            // arrange
            val productIds = listOf(productOf(brandId), productOf(brandId, stock = 0))
            val otherProductId = productOf(otherBrandId)
            val deletedId = productJpaRepository.save(ProductFixture.deletedProduct(brandId = brandId)).productId
            val deletedAtBefore = product(deletedId).deletedAt
            val orderId = draftOrderOn(productIds.first())
            val paidOrder = paidOrderOn(productOf(brandId))
            val paidBefore = viewOf(paidOrder)
            every { brandService.delete(any()) } throws IllegalStateException("브랜드 삭제 중간 실패")

            // act
            assertThrows<IllegalStateException> { brandFacade.delete(requester, brandId) }

            // assert · 상품 UPDATE 가 먼저 나갔다는 증거로, 실패 지점이 브랜드 삭제 호출이었음을 확인
            verify(exactly = 1) { brandService.delete(brandId) }
            assertAll(
                { assertThat(brand(brandId).deletedAt).isNull() },
                { productIds.forEach { assertThat(product(it).deletedAt).isNull() } },
                { assertThat(product(otherProductId).deletedAt).isNull() },
                { assertThat(product(deletedId).deletedAt).isEqualTo(deletedAtBefore) },
                { assertThat(orderJpaRepository.findById(orderId).get().status).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(viewOf(paidOrder)).isEqualTo(paidBefore) },
            )
        }
    }
}
