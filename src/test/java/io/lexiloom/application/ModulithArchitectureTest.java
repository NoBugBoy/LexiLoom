package io.lexiloom.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTest {

  // 1. 扫描加载当前应用的所有 Modulith 模块 (user, document, ai 等)
  ApplicationModules modules = ApplicationModules.of(LexiLoomApplication.class);

  @Test
  @DisplayName("校验 Modulith 模块架构：无循环依赖、无包私有越界")
  void verifyModuleStructure() {
    // 核心：自动断言模块隔离性，如有循环依赖或非法跨模块引用直接报错
    modules.verify();
  }

  @Test
  @DisplayName("公开 api 包不得暴露 Entity / Request / Response 等模块内部类型")
  void apiPackagesMustNotExposeInternalModels() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.lexiloom.application");

    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..model.entity..", "..model.request..", "..model.response..")
        .because("跨模块公开 API 只能暴露 DTO，禁止泄露 Entity 与 Controller 层 Request/Response")
        .check(classes);
  }
}
