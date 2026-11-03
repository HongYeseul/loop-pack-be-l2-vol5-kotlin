package com.loopers.product.domain.event

import com.fasterxml.jackson.annotation.JsonValue

enum class ProductEventType(@get:JsonValue val eventName: String) {
    PRODUCT_DETAIL_QUERIED("ProductDetailQueried"),
    PRODUCT_LIST_QUERIED("ProductListQueried"),
    ;

    companion object {
        const val TOPIC = "product-events"
    }
}
