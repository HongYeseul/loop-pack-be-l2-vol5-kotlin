package com.loopers.coupon.domain

enum class CouponFailure { BAD_REQUEST, CAMPAIGN_NOT_FOUND, SOLD_OUT }

class CouponBusinessException(val failure: CouponFailure) : RuntimeException(failure.name)
