package com.loopers.purchase

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("구매 활동 도메인의 계층 의존 규칙")
class PurchaseArchitectureTest {
    @Test
    @DisplayName("도메인·애플리케이션·인터페이스가 허용된 방향으로만 계층을 참조한다")
    fun keepsDomainLayerDirection() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.loopers.purchase")
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..").check(classes)
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..interfaces..", "..infrastructure..").check(classes)
        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..").check(classes)
    }
}
