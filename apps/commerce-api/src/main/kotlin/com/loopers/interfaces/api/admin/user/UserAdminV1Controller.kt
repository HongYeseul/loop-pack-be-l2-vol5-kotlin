package com.loopers.interfaces.api.admin.user

import com.loopers.application.user.UserAdminFacade
import com.loopers.domain.admin.AdminLoginId
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/users")
class UserAdminV1Controller(
    private val userAdminFacade: UserAdminFacade,
) : UserAdminV1ApiSpec {
    @GetMapping("/{userId}")
    override fun getUser(
        @PathVariable(value = "userId") userId: Long,
        requester: AdminLoginId,
    ): ApiResponse<UserAdminV1Dto.UserResponse> =
        userAdminFacade.getMasked(targetUserId = userId, requester = requester)
            .let { UserAdminV1Dto.UserResponse.from(it) }
            .let { ApiResponse.success(it) }

    /** `purpose` 는 required 다. 빠지면 핸들러에 닿기 전에 `BAD_REQUEST` 가 된다 (A-15). */
    @GetMapping("/{userId}/unmasked")
    override fun getUnmaskedUser(
        @PathVariable(value = "userId") userId: Long,
        @RequestParam(value = "purpose") purpose: String,
        requester: AdminLoginId,
    ): ApiResponse<UserAdminV1Dto.UnmaskedUserResponse> =
        userAdminFacade.getUnmasked(targetUserId = userId, requester = requester, purpose = purpose)
            .let { UserAdminV1Dto.UnmaskedUserResponse.from(it) }
            .let { ApiResponse.success(it) }
}
