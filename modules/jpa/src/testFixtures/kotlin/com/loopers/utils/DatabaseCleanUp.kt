package com.loopers.utils

import jakarta.persistence.CollectionTable
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DatabaseCleanUp(
    @PersistenceContext private val entityManager: EntityManager,
) : InitializingBean {
    private val tableNames = mutableListOf<String>()

    override fun afterPropertiesSet() {
        entityManager.metamodel.entities
            .map { entity -> entity.javaType }
            .filter { type -> type.getAnnotation(Entity::class.java) != null }
            .forEach { type ->
                tableNames.add(type.getAnnotation(Table::class.java).name)
                tableNames.addAll(collectionTableNamesOf(type))
            }
    }

    /**
     * `@ElementCollection` 의 컬렉션 테이블은 **엔티티가 아니라 `metamodel.entities` 에 없다.**
     * 빠뜨리면 부모 행만 지워지고 자식 행이 남아, 같은 id 로 다시 만들어진 부모에 앞 테스트의 값이 붙는다.
     */
    private fun collectionTableNamesOf(type: Class<*>): List<String> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .mapNotNull { field -> field.getAnnotation(CollectionTable::class.java)?.name }
            .filter { it.isNotBlank() }
            .toList()

    @Transactional
    fun truncateAllTables() {
        entityManager.flush()
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate()
        tableNames.forEach { table ->
            entityManager.createNativeQuery("TRUNCATE TABLE `$table`").executeUpdate()
        }
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate()
    }
}
