package com.loopers.brand.interfaces.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.product.domain.ProductStatus
import com.loopers.admin.domain.AdminRole
import com.loopers.fixture.AdminUserFixture
import com.loopers.admin.infrastructure.AdminUserJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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

/** 관리자 경계가 ROLE_ADMIN 을 요구하므로 역할을 실을 수 있는 MockMvc 사용 */
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

    /** 관리자 경계 통과와 계정 존재는 별개, 권한 검사는 이 행을 조회 */
    @BeforeEach
    fun setUp() {
        adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "admin", roles = arrayOf(AdminRole.SUPER_ADMIN)))
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun admin() = user("admin").roles("ADMIN")

    /** 다른 역할로 같은 요청 전송, 역할마다 계정이 달라야 UNIQUE(login_id) 충돌 없음 */
    private fun adminWith(role: AdminRole, loginId: String = "other1") =
        user(loginId).roles("ADMIN").also {
            adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = loginId, roles = arrayOf(role)))
        }

    private fun body(vararg pairs: Pair<String, Any?>) = objectMapper.writeValueAsString(pairs.toMap())

    @DisplayName("GET /api-admin/v1/brands · 목록")
    @Nested
    inner class GetAll {
        @DisplayName("삭제된 브랜드도 함께 나온다. 왜 안 지워지는지 판단하려면 봐야 한다.")
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

        @DisplayName("페이지 규격을 벗어나면, INVALID_PAGE 로 거절한다.")
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

        @DisplayName("일반 사용자는 CSRF 토큰이 유효해도 거절된다. 막는 것은 역할이다.")
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
        @DisplayName("삭제 시각과 생성·수정 시각까지 보여준다.")
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

        @DisplayName("연결된 상품 수를 함께 보여준다. 이것이 왜 안 지워지는지에 대한 답이다.")
        @Test
        fun showsProductCount() {
            // arrange · 재고 0 도 단종도 연결로 계산, 삭제된 것만 제외
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

        @DisplayName("삭제된 브랜드는 고칠 수 없다. 없는 것과 같은 오류로 답한다.")
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
        @DisplayName("행은 남기고 삭제 시각만 남긴다 (논리 삭제).")
        @Test
        fun softDeletes() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())

            // act
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            // assert · 행이 사라지지 않음
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

        /** 연결 기준은 삭제 여부뿐, 재고·판매 상태와 무관하게 모두 함께 지워짐 */
        @DisplayName("연결된 상품도 함께 지운다. 재고 0·판매중지·단종 상품도 포함한다.")
        @Test
        fun deletesLinkedProductsToo() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())
            val productIds = listOf(
                ProductFixture.product(brandId = brand.brandId),
                ProductFixture.product(brandId = brand.brandId, stock = 0),
                ProductFixture.product(brandId = brand.brandId, status = ProductStatus.SUSPENDED),
                ProductFixture.product(brandId = brand.brandId, status = ProductStatus.DISCONTINUED),
            ).map { productJpaRepository.save(it).productId }

            // act
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            // assert
            assertThat(brandJpaRepository.findById(brand.id).get().deletedAt).isNotNull()
            assertThat(productJpaRepository.findAllById(productIds)).hasSize(4).allSatisfy { assertThat(it.deletedAt).isNotNull() }
        }

        @DisplayName("없는 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        fun rejectsUnknownBrand() {
            mockMvc.perform(delete("$ENDPOINT/999").with(admin()).with(csrf()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BRAND_NOT_FOUND.code))
        }

        @DisplayName("연결 상품이 모두 논리 삭제됐으면 지울 수 있다.")
        @Test
        fun allowsWhenAllProductsDeleted() {
            // arrange · 같은 deletedAt 을 고객은 "없는 상품", 브랜드 삭제 판단은 "연결 안 됨"으로 해석
            val brand = brandJpaRepository.save(BrandFixture.brand())
            productJpaRepository.save(ProductFixture.deletedProduct(brandId = brand.brandId))

            // act & assert
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            assertThat(brandJpaRepository.findAll().first().deletedAt).isNotNull()
        }

        @DisplayName("다른 브랜드의 상품은 지우지 않는다.")
        @Test
        fun ignoresOtherBrandsProduct() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand(name = "지울 브랜드"))
            val other = brandJpaRepository.save(BrandFixture.brand(name = "다른 브랜드"))
            val otherProduct = productJpaRepository.save(ProductFixture.product(brandId = other.brandId))

            // act
            mockMvc.perform(delete("$ENDPOINT/${brand.id}").with(admin()).with(csrf()))
                .andExpect(status().isOk)

            // assert
            assertThat(productJpaRepository.findById(otherProduct.id).get().deletedAt).isNull()
        }
    }

    @DisplayName("역할마다 할 수 있는 일이 다르다 (최소 권한)")
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
