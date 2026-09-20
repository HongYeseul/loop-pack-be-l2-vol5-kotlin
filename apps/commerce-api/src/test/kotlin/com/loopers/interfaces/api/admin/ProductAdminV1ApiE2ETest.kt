package com.loopers.interfaces.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.product.ProductStatus
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A-6 ~ A-11 · A-16 · 관리자 상품 CRUD · 재고 · 판매 상태.
 *
 * MockMvc 를 쓰는 이유는 브랜드 쪽과 같다 — 관리자 경계가 `ROLE_ADMIN` 을 요구하고(P-03),
 * 요청에 역할을 실어 보낼 수 있는 것이 MockMvc 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductAdminV1ApiE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/products"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun admin() = user("admin").roles("ADMIN")

    private fun body(vararg pairs: Pair<String, Any?>) = objectMapper.writeValueAsString(pairs.toMap())

    private fun brandId(name: String = BrandFixture.DEFAULT_NAME): Long = brandJpaRepository.save(BrandFixture.brand(name)).brandId

    @DisplayName("GET /api-admin/v1/products · 목록 (A-6)")
    @Nested
    inner class GetAll {
        @DisplayName("삭제된 상품도 함께 나오고, 재고 수량과 삭제 시각이 보인다 (P-33 · D-10).")
        @Test
        fun includesDeletedWithStock() {
            // arrange
            val brand = brandId()
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "살아있는 상품", stock = 7))
            productJpaRepository.save(ProductFixture.deletedProduct(brandId = brand, name = "지워진 상품"))

            // act & assert
            mockMvc.perform(get(ENDPOINT).with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[*].name", hasItem("지워진 상품")))
                .andExpect(jsonPath("$.data.items[*].stock", hasItem(7)))
                .andExpect(jsonPath("$.data.items[0].brandName").value(BrandFixture.DEFAULT_NAME))
        }

        @DisplayName("판매중지·단종된 상품도 나온다. 목록에서 거르는 것은 고객 쪽뿐이다 (P-39).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun includesNotOnSale(status: ProductStatus) {
            // arrange
            productJpaRepository.save(ProductFixture.product(brandId = brandId(), name = "안 팖", status = status))

            // act & assert
            mockMvc.perform(get(ENDPOINT).with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.items[0].name").value("안 팖"))
                .andExpect(jsonPath("$.data.items[0].status").value(status.name))
        }
    }

    @DisplayName("POST /api-admin/v1/products · 생성 (A-7)")
    @Nested
    inner class Create {
        @DisplayName("살아 있는 브랜드에 만들 수 있다. 판매중으로 시작한다 (P-05 · P-36).")
        @Test
        fun createsOnAliveBrand() {
            // arrange
            val brand = brandId("루퍼스")

            // act & assert
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("brandId" to brand, "name" to "티셔츠", "price" to 3_500, "stock" to 10)),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.brandId").value(brand))
                .andExpect(jsonPath("$.data.brandName").value("루퍼스"))
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist())
        }

        @DisplayName("재고를 생략하면 0 으로 시작한다. 재고는 A-11 로 따로 설정한다 (기획 S-1).")
        @Test
        fun defaultsStockToZero() {
            // act & assert
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("brandId" to brandId(), "name" to "티셔츠", "price" to 3_500)),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.stock").value(0))
                .andExpect(jsonPath("$.data.purchasable").value(false))
        }

        @DisplayName("삭제된 브랜드에는 만들 수 없다 (P-05 · P-12).")
        @Test
        fun rejectsDeletedBrand() {
            // arrange
            val deletedBrand = brandJpaRepository.save(BrandFixture.deletedBrand()).brandId

            // act & assert
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("brandId" to deletedBrand, "name" to "티셔츠", "price" to 3_500)),
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_NOT_FOUND.code))

            assertThat(productJpaRepository.count()).isZero()
        }

        @DisplayName("이름·가격이 범위를 벗어나면 거절하고, 아무것도 저장하지 않는다 (P-06).")
        @Test
        fun rejectsOutOfRange() {
            // act & assert
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("brandId" to brandId(), "name" to "티셔츠", "price" to -1)),
            )
                .andExpect(status().isBadRequest)

            assertThat(productJpaRepository.count()).isZero()
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{id} · 수정 (A-9)")
    @Nested
    inner class Update {
        @DisplayName("이름과 가격만 바뀐다. 브랜드는 그대로다 (P-05).")
        @Test
        fun changesNameAndPriceOnly() {
            // arrange
            val brand = brandId()
            val other = brandId("다른 브랜드")
            val product = productJpaRepository.save(ProductFixture.product(brandId = brand))

            // act · brandId 를 보내도 계약에 없는 필드라 무시된다
            mockMvc.perform(
                put("$ENDPOINT/${product.id}").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "후드", "price" to 12_000, "brandId" to other)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.name").value("후드"))
                .andExpect(jsonPath("$.data.price").value(12_000))
                .andExpect(jsonPath("$.data.brandId").value(brand))
        }

        @DisplayName("삭제된 상품은 수정 대상이 아니다 (P-12).")
        @Test
        fun rejectsDeleted() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.deletedProduct(brandId = brandId()))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "후드", "price" to 12_000)),
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.PRODUCT_NOT_FOUND.code))
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{id}/stock · 재고 설정 (A-11)")
    @Nested
    inner class ChangeStock {
        @DisplayName("증감이 아니라 최종 수량이다. 두 번 보내도 결과가 같다 (P-07).")
        @Test
        fun setsFinalQuantity() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 5))

            // act
            repeat(2) {
                mockMvc.perform(
                    put("$ENDPOINT/${product.id}/stock").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("quantity" to 3)),
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.stock").value(3))
            }

            // assert · 5 - 3 - 3 = -1 이 아니다
            assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(3)
        }

        @DisplayName("0 으로 설정하면 구매 불가가 된다. 저장된 판매 상태는 그대로 판매중이다 (P-37).")
        @Test
        fun zeroMakesItNotPurchasable() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 5))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}/stock").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("quantity" to 0)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.stock").value(0))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.purchasable").value(false))
        }

        @DisplayName("음수는 거절하고 재고를 그대로 둔다 (P-07).")
        @Test
        fun rejectsNegative() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 5))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}/stock").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("quantity" to -1)),
            )
                .andExpect(status().isBadRequest)

            assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(5)
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{id}/status · 판매 상태 설정 (A-16)")
    @Nested
    inner class ChangeStatus {
        @DisplayName("판매중지했다가 다시 판매중으로 되돌릴 수 있다 (P-36).")
        @Test
        fun suspendsAndResumes() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId()))

            // act & assert
            listOf(ProductStatus.SUSPENDED, ProductStatus.ON_SALE).forEach { next ->
                mockMvc.perform(
                    put("$ENDPOINT/${product.id}/status").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("status" to next.name)),
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.status").value(next.name))
            }
        }

        @DisplayName("단종은 되돌릴 수 없다. 상태는 단종으로 남는다 (P-36).")
        @Test
        fun cannotUndoDiscontinued() {
            // arrange
            val product = productJpaRepository.save(
                ProductFixture.product(brandId = brandId(), status = ProductStatus.DISCONTINUED),
            )

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}/status").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("status" to ProductStatus.ON_SALE.name)),
            )
                .andExpect(status().isBadRequest)

            assertThat(productJpaRepository.findById(product.id).get().status).isEqualTo(ProductStatus.DISCONTINUED)
        }

        @DisplayName("같은 상태로 다시 보내도 성공한다. 전이가 아니라 최종 상태 설정이다 (설계 DS-11).")
        @Test
        fun settingTheSameStatusSucceeds() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId()))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}/status").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("status" to ProductStatus.ON_SALE.name)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
        }

        /** **금지를 타입이 지킨다** (P-37). `SOLD_OUT` 은 `ProductStatus` 에 없어서 Jackson 이 먼저 거절한다. */
        @DisplayName("재고없음으로는 설정할 수 없다. 재고에서 따라오는 값이지 설정 대상이 아니다 (P-37).")
        @Test
        fun cannotSetSoldOut() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 5))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${product.id}/status").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("status" to "SOLD_OUT")),
            )
                .andExpect(status().isBadRequest)

            assertAll(
                { assertThat(productJpaRepository.findById(product.id).get().status).isEqualTo(ProductStatus.ON_SALE) },
                { assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(5) },
            )
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{id} · 삭제 (A-10)")
    @Nested
    inner class Delete {
        @DisplayName("논리 삭제다. 행은 남고 삭제 시각만 생긴다 (D-2).")
        @Test
        fun softDeletes() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId()))

            // act
            mockMvc.perform(delete("$ENDPOINT/${product.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            // assert
            assertAll(
                { assertThat(productJpaRepository.count()).isEqualTo(1L) },
                { assertThat(productJpaRepository.findById(product.id).get().deletedAt).isNotNull() },
            )
        }
    }

    @DisplayName("관리자 경계 (P-03)")
    @Nested
    inner class Boundary {
        @DisplayName("일반 사용자는 유효한 CSRF 를 실어도 통과하지 못한다. 막는 것은 역할이다.")
        @Test
        fun rejectsNonAdmin() {
            mockMvc.perform(get(ENDPOINT).with(user("user").roles("USER")))
                .andExpect(status().isForbidden)

            mockMvc.perform(
                post(ENDPOINT).with(user("user").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("brandId" to 1, "name" to "티셔츠", "price" to 3_500)),
            )
                .andExpect(status().isForbidden)
        }

        @DisplayName("식별 없는 요청도 통과하지 못한다.")
        @Test
        fun rejectsAnonymous() {
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden)
        }
    }
}
