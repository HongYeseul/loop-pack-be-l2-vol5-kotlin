package com.loopers.interfaces.api.admin.user

import com.loopers.domain.admin.AdminLoginId
import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "User Admin V1 API", description = "관리자용 구매자 API 입니다. 조회는 식별자 단건만 있습니다 (P-34).")
interface UserAdminV1ApiSpec {
    @Operation(summary = "구매자 조회", description = "식별자와 표시 이름이 마스킹되어 나갑니다 (P-35). CUSTOMER_READ_MASKED 가 필요합니다.")
    fun getUser(userId: Long, requester: AdminLoginId): ApiResponse<UserAdminV1Dto.UserResponse>

    @Operation(
        summary = "구매자 마스킹 해제 조회",
        description = "purpose 가 필수입니다. 요청자·시각·목적이 기록됩니다 (P-35 · D-12). CUSTOMER_READ_UNMASKED 가 필요합니다.",
    )
    fun getUnmaskedUser(userId: Long, purpose: String, requester: AdminLoginId): ApiResponse<UserAdminV1Dto.UnmaskedUserResponse>
}
