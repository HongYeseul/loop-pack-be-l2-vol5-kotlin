package com.loopers.config

import org.hibernate.boot.model.relational.Namespace
import org.hibernate.boot.model.relational.Sequence
import org.hibernate.mapping.Table
import org.hibernate.tool.schema.spi.SchemaFilter
import org.hibernate.tool.schema.spi.SchemaFilterProvider
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PurchaseJpaConfig {
    @Bean
    fun purchaseSchemaCustomizer(): HibernatePropertiesCustomizer = HibernatePropertiesCustomizer { properties ->
        // 주문 원본 테이블의 생성·변경·삭제는 commerce-api에서 관리
        val filter = object : SchemaFilter {
            override fun includeNamespace(namespace: Namespace): Boolean = true

            override fun includeTable(table: Table): Boolean = table.name !in setOf("orders", "order_item")

            override fun includeSequence(sequence: Sequence): Boolean = true
        }
        properties["hibernate.hbm2ddl.schema_filter_provider"] = object : SchemaFilterProvider {
            override fun getCreateFilter(): SchemaFilter = filter

            override fun getDropFilter(): SchemaFilter = filter

            override fun getMigrateFilter(): SchemaFilter = filter

            override fun getTruncatorFilter(): SchemaFilter = filter

            override fun getValidateFilter(): SchemaFilter = SchemaFilter.ALL
        }
    }
}
