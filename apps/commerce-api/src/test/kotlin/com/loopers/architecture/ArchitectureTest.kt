package com.loopers.architecture

import com.tngtech.archunit.base.DescribedPredicate.describe
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers")

    @DisplayName("계층은 정해진 방향으로만 의존한다.")
    @Test
    fun respectsLayerDependencies() {
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(classes)

        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(classes)

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes)
    }

    /**
     * 설계 1-3절 4번 규칙.
     *
     * 1~3번만으로는 Controller 가 `ProductService` 를 직접 불러 `application` 을 건너뛰는 것을 막지 못한다.
     * 막는 것은 domain 의 **행동과 저장** 이고, **값**(enum, 값 객체 — 예: `LoginId`)을 쓰는 것은 허용한다.
     * 더 세게 막으면 `ProductSort` 같은 도메인 어휘를 `application` 에 복제해야 하고, 그 복제가 새 불일치를 만든다.
     */
    @DisplayName("interfaces 는 domain 의 행동·저장을 건너뛰어 부르지 않는다.")
    @Test
    fun doesNotLetInterfacesCallDomainServiceOrRepository() {
        val domainServiceOrRepository = describe<JavaClass>(
            "..domain.. 의 *Service · *Repository",
        ) { javaClass ->
            javaClass.packageName.contains(".domain.") &&
                (javaClass.simpleName.endsWith("Service") || javaClass.simpleName.endsWith("Repository"))
        }

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat(domainServiceOrRepository)
            .check(classes)
    }
}
