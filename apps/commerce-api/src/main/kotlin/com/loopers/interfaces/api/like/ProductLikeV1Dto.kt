package com.loopers.interfaces.api.like

import com.loopers.application.like.ProductLikeInfo

class ProductLikeV1Dto {
    /** 등록·취소 공통 응답 (P-31). 요청 방향이 아니라 **끝난 뒤의 상태**다. */
    data class LikeResponse(
        val liked: Boolean,
        val likeCount: Long,
    ) {
        companion object {
            fun from(info: ProductLikeInfo): LikeResponse = LikeResponse(liked = info.liked, likeCount = info.likeCount)
        }
    }
}
