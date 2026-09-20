package com.loopers.interfaces.api.admin.brand

import com.loopers.application.brand.BrandFacade
import com.loopers.domain.support.PageCriteria
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/brands")
class BrandAdminV1Controller(
    private val brandFacade: BrandFacade,
) : BrandAdminV1ApiSpec {
    @GetMapping
    override fun getBrands(
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
    ): ApiResponse<PageResponse<BrandAdminV1Dto.BrandResponse>> =
        brandFacade.getAllForAdmin(PageCriteria.of(page, size))
            .let { PageResponse.from(it, BrandAdminV1Dto.BrandResponse::from) }
            .let { ApiResponse.success(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun createBrand(
        @RequestBody request: BrandAdminV1Dto.CreateRequest,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> =
        brandFacade.create(request.name)
            .let { BrandAdminV1Dto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{brandId}")
    override fun getBrand(
        @PathVariable(value = "brandId") brandId: Long,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> =
        brandFacade.getForAdmin(brandId)
            .let { BrandAdminV1Dto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PutMapping("/{brandId}")
    override fun updateBrand(
        @PathVariable(value = "brandId") brandId: Long,
        @RequestBody request: BrandAdminV1Dto.UpdateRequest,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse> =
        brandFacade.changeName(brandId, request.name)
            .let { BrandAdminV1Dto.BrandResponse.from(it) }
            .let { ApiResponse.success(it) }

    @DeleteMapping("/{brandId}")
    override fun deleteBrand(
        @PathVariable(value = "brandId") brandId: Long,
    ): ApiResponse<Any> {
        brandFacade.delete(brandId)
        return ApiResponse.success()
    }
}
