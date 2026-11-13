package com.loopers.coupon.application

import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

fun CouponStreamException.asCoreException(): CoreException = CoreException(
    when (failure) {
        CouponStreamFailure.FULL -> ErrorType.COUPON_RESERVATIONS_FULL
        CouponStreamFailure.RECOVERY_CONFLICT -> ErrorType.COUPON_ADMISSION_RECOVERY_CONFLICT
        CouponStreamFailure.INVALID_REQUEST -> ErrorType.BAD_REQUEST
        else -> ErrorType.COUPON_ADMISSION_UNAVAILABLE
    },
)
