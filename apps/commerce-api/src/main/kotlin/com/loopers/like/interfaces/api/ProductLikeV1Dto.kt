package com.loopers.like.interfaces.api

import com.loopers.like.application.ProductLikeInfo

class ProductLikeV1Dto {
    /** 등록·취소 공통 응답, 요청 방향이 아닌 종료 후 상태 */
    data class LikeResponse(
        val liked: Boolean,
        val likeCount: Long,
    ) {
        companion object {
            fun from(info: ProductLikeInfo): LikeResponse = LikeResponse(liked = info.liked, likeCount = info.likeCount)
        }
    }
}
