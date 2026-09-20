package com.loopers.interfaces.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.admin.AdminRole
import com.loopers.fixture.AdminUserFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
 * A-1 ~ A-5 · 관리자 브랜드 CRUD.
 *
 * `TestRestTemplate` 이 아니라 MockMvc 를 쓰는 이유: 관리자 경계는 `ROLE_ADMIN` 을 요구하는데
 * (P-03), 요청에 역할을 실어 보낼 수 있는 것이 MockMvc 다. 과제가 지정한 조합이기도 하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandAdminV1ApiE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val brandJpaRepository: BrandJpaRepository,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/brands"
    }

    /** 관리자 경계를 통과하는 것과 계정이 있는 것은 다르다 (P-43). 권한 검사가 보는 것은 이 행이다. */
    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "admin", roles = arrayOf(AdminRole.SUPER_ADMIN)))
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun admin() = user("admin").roles("ADMIN")

    /** 다른 역할로 같은 요청을 보낸다. 역할마다 계정이 달라야 UNIQUE(login_id) 를 건드리지 않는다. */
    private fun adminWith(role: AdminRole, loginId: String = "other1") =
        user(loginId).roles("ADMIN").also {
            adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = loginId, roles = arrayOf(role)))
        }

    private fun body(vararg pairs: Pair<String, Any?>) = objectMapper.writeValueAsString(pairs.toMap())

    @DisplayName("GET /api-admin/v1/brands · 목록")
    @Nested
    inner class GetAll {
        @DisplayName("삭제된 브랜드도 함께 나온다 (P-33). 왜 안 지워지는지 판단하려면 봐야 한다.")
        @Test
        fun includesDeleted() {
            // arrange
            brandJpaRepository.save(BrandFixture.brand(name = "살아있는 브랜드"))
            brandJpaRepository.save(BrandFixture.deletedBrand(name = "지워진 브랜드"))

            // act & assert
            mockMvc.perform(get(ENDPOINT).with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[*].name", org.hamcrest.Matchers.hasItem("지워진 브랜드")))
        }

        @DisplayName("페이지 규격을 벗어나면, INVALID_PAGE 로 거절한다 (설계 6-1절).")
        @Test
        fun rejectsInvalidPage() {
            mockMvc.perform(get("$ENDPOINT?page=0&size=101").with(admin()))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.INVALID_PAGE.code))
        }
    }

    @DisplayName("POST /api-admin/v1/brands · 생성")
    @Nested
    inner class Create {
        @DisplayName("브랜드를 만들고 201 로 답한다.")
        @Test
        fun createsBrand() {
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스")),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.name").value("루퍼스"))

            assertThat(brandJpaRepository.findAll().map { it.name }).containsExactly("루퍼스")
        }

        @DisplayName("이름이 규격을 벗어나면 거절하고, 아무것도 만들지 않는다.")
        @Test
        fun rejectsBlankName() {
            mockMvc.perform(
                post(ENDPOINT).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "  ")),
            )
                .andExpect(status().isBadRequest)

            assertThat(brandJpaRepository.findAll()).isEmpty()
        }

        @DisplayName("유효한 CSRF 토큰이 없으면 거절한다. 관리자 역할이어도 마찬가지다.")
        @Test
        fun rejectsWithoutCsrf() {
            mockMvc.perform(
                post(ENDPOINT).with(admin())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스")),
            )
                .andExpect(status().isForbidden)

            assertThat(brandJpaRepository.findAll()).isEmpty()
        }

        @DisplayName("일반 사용자는 CSRF 토큰이 유효해도 거절된다. 막는 것은 역할이다 (P-03).")
        @Test
        fun rejectsNonAdminEvenWithCsrf() {
            mockMvc.perform(
                post(ENDPOINT).with(user("customer").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스")),
            )
                .andExpect(status().isForbidden)
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{id} · 상세")
    @Nested
    inner class Get {
        @DisplayName("삭제 시각과 생성·수정 시각까지 보여준다 (P-33 · D-10).")
        @Test
        fun showsAdminOnlyFields() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.deletedBrand())

            // act & assert
            mockMvc.perform(get("$ENDPOINT/${brand.id}").with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.deletedAt").exists())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists())
        }

        @DisplayName("연결된 상품 수를 함께 보여준다 (A-3 · D-10). 이것이 왜 안 지워지는지에 대한 답이다 (P-11).")
        @Test
        fun showsProductCount() {
            // arrange · 재고 0 도 단종도 연결로 센다 (DS-11). 삭제된 것만 빠진다
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.product(brandId = brand.brandId, stock = 0))
            productJpaRepository.save(ProductFixture.product(brandId = brand.brandId, status = ProductStatus.DISCONTINUED))
            productJpaRepository.save(ProductFixture.deletedProduct(brandId = brand.brandId))

            // act & assert
            mockMvc.perform(get("$ENDPOINT/${brand.id}").with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.productCount").value(2))
        }

        @DisplayName("연결된 상품이 없으면 0 이다.")
        @Test
        fun showsZeroWhenNoProduct() {
            val brand = brandJpaRepository.save(BrandFixture.brand())

            mockMvc.perform(get("$ENDPOINT/${brand.id}").with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.productCount").value(0))
        }

        @DisplayName("없는 브랜드는 BRAND_NOT_FOUND 로 답한다.")
        @Test
        fun returnsBrandNotFound_whenAbsent() {
            mockMvc.perform(get("$ENDPOINT/999999").with(admin()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_NOT_FOUND.code))
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{id} · 수정")
    @Nested
    inner class Update {
        @DisplayName("이름을 바꾼다.")
        @Test
        fun changesName() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand(name = "루퍼스"))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${brand.id}").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스 랩")),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.name").value("루퍼스 랩"))
        }

        @DisplayName("삭제된 브랜드는 고칠 수 없다 (P-12). 없는 것과 같은 오류로 답한다.")
        @Test
        fun rejectsDeletedBrand() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.deletedBrand(name = "루퍼스"))

            // act & assert
            mockMvc.perform(
                put("$ENDPOINT/${brand.id}").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스 랩")),
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_NOT_FOUND.code))

            assertThat(brandJpaRepository.findAll().first().name).isEqualTo("루퍼스")
        }

        @DisplayName("유효한 CSRF 토큰이 없으면 거절한다.")
        @Test
        fun rejectsWithoutCsrf() {
            val brand = brandJpaRepository.save(BrandFixture.brand(name = "루퍼스"))

            mockMvc.perform(
                put("$ENDPOINT/${brand.id}").with(admin())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스 랩")),
            )
                .andExpect(status().isForbidden)

            assertThat(brandJpaRepository.findAll().first().name).isEqualTo("루퍼스")
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{id} · 삭제")
    @Nested
    inner class Delete {
        @DisplayName("행은 남기고 삭제 시각만 남긴다 (D-2 · 논리 삭제).")
        @Test
        fun softDeletes() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())

            // act
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            // assert · 행이 사라지지 않았다
            val found = brandJpaRepository.findAll()
            assertThat(found).hasSize(1)
            assertThat(found.first().deletedAt).isNotNull()
        }

        @DisplayName("유효한 CSRF 토큰이 없으면 거절하고, 지우지 않는다.")
        @Test
        fun rejectsWithoutCsrf() {
            val brand = brandJpaRepository.save(BrandFixture.brand())

            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()))
                .andExpect(status().isForbidden)

            assertThat(brandJpaRepository.findAll().first().deletedAt).isNull()
        }

        /**
         * **P-11 이 이 단계에서 완성된다.** 2단계에는 상품이 없어서 "연결된 상품" 을 만들 수 없었다.
         *
         * 세는 기준은 "삭제되지 않았는가" 하나다 — 재고도 판매 상태도 보지 않는다 (DS-11).
         */
        @DisplayName("살아 있는 상품이 연결되어 있으면 BRAND_HAS_PRODUCTS 로 거절하고, 지우지 않는다 (P-11).")
        @Test
        fun rejectsWhenAliveProductExists() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.product(brandId = brand.brandId))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_HAS_PRODUCTS.code))

            assertThat(brandJpaRepository.findAll().first().deletedAt).isNull()
        }

        @DisplayName("재고 0 인 상품도 연결로 센다 (P-11 · 설계 6-4절). 브랜드 쪽에서 재고 0 은 아직 살아 있는 상품이다.")
        @Test
        fun countsOutOfStockProduct() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.product(brandId = brand.brandId, stock = 0))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_HAS_PRODUCTS.code))
        }

        @DisplayName("판매중지·단종된 상품도 연결로 센다 (P-11 · DS-11). 판매 상태는 삭제와 다른 축이다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun countsNotOnSaleProduct(status: ProductStatus) {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.product(brandId = brand.brandId, status = status))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_HAS_PRODUCTS.code))
        }

        @DisplayName("연결 상품이 모두 논리 삭제됐으면 지울 수 있다 (P-11 · 설계 6-4절 · 기획 S-1 의 6~7번).")
        @Test
        fun allowsWhenAllProductsDeleted() {
            // arrange · 같은 deletedAt 을 두 질문이 다르게 읽는다 — 고객에게는 "없는 상품",
            // 브랜드 삭제 판단에서는 "연결 안 됨" (기획 2-3절)
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.deletedProduct(brandId = brand.brandId))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            assertThat(brandJpaRepository.findAll().first().deletedAt).isNotNull()
        }

        @DisplayName("다른 브랜드의 상품은 세지 않는다.")
        @Test
        fun ignoresOtherBrandsProduct() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand(name = "지울 브랜드"))
            val other = brandJpaRepository.save(BrandFixture.brand(name = "다른 브랜드"))
            productJpaRepository.save(ProductFixture.product(brandId = other.brandId))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)
        }
    }

    @DisplayName("역할마다 할 수 있는 일이 다르다 (P-43 · D-12 최소 권한)")
    @Nested
    inner class Permission {
        @DisplayName("주문 담당은 브랜드 목록은 본다. ORDER_ADMIN 이 CATALOG_READ 를 가진다.")
        @Test
        fun allowsOrderAdminToRead() {
            mockMvc.perform(get(ENDPOINT).with(adminWith(AdminRole.ORDER_ADMIN)))
                .andExpect(status().isOk)
        }

        @DisplayName("같은 역할이 브랜드를 만들지는 못한다. 읽기와 쓰기가 갈린다.")
        @Test
        fun rejectsOrderAdminWrite() {
            mockMvc.perform(
                post(ENDPOINT).with(adminWith(AdminRole.ORDER_ADMIN)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("name" to "루퍼스")),
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.ADMIN_PERMISSION_DENIED.code))
        }
    }
}
