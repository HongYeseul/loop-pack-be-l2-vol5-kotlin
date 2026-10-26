package com.loopers.admin.domain

/** 역할보다 세분화한 권한 단위, 개인정보 안전성 확보조치 기준 대응 */
enum class AdminPermission {
    CATALOG_READ,
    CATALOG_WRITE,
    ORDER_READ,
    CUSTOMER_READ_MASKED,
    CUSTOMER_READ_UNMASKED,
    ADMIN_MANAGE,
}
