package io.lexiloom.application.common.storage.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.exception.BusinessException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoragePathGuardTest {

  private static final Path ROOT = Paths.get("rustfs-data", "lexiloom");

  @Test
  @DisplayName("对象键净化：剥离 ../ 目录穿越片段与多余分隔符")
  void testSanitizeObjectKeyStripsTraversalSegments() {
    assertThat(StoragePathGuard.sanitizeObjectKey("workspaces/1/docs/../../../etc/passwd"))
        .isEqualTo("workspaces/1/docs/etc/passwd");
    assertThat(StoragePathGuard.sanitizeObjectKey("/workspaces/1//docs/a.md"))
        .isEqualTo("workspaces/1/docs/a.md");
    assertThat(StoragePathGuard.sanitizeObjectKey("..\\..\\windows\\system32"))
        .isEqualTo("windows/system32");
  }

  @Test
  @DisplayName("对象键净化：空白键或纯穿越键直接拒绝")
  void testSanitizeObjectKeyRejectsBlankAndPureTraversal() {
    assertThatThrownBy(() -> StoragePathGuard.sanitizeObjectKey(" "))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> StoragePathGuard.sanitizeObjectKey("../../"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("落盘路径解析：合法键始终落在存储根目录内")
  void testResolveWithinKeepsPathInsideRoot() {
    Path resolved = StoragePathGuard.resolveWithin(ROOT, "workspaces/1/docs/a.md");

    assertThat(resolved.startsWith(ROOT.toAbsolutePath().normalize())).isTrue();
    assertThat(resolved.getFileName().toString()).isEqualTo("a.md");
  }

  @Test
  @DisplayName("落盘路径解析：穿越键被归一化后仍限制在根目录内，不会写出存储根")
  void testResolveWithinRejectsEscapeAttempt() {
    Path resolved =
        StoragePathGuard.resolveWithin(ROOT, "workspaces/1/docs/../../../../../../etc/passwd");

    assertThat(resolved.startsWith(ROOT.toAbsolutePath().normalize())).isTrue();
    assertThat(resolved.toString()).doesNotContain("..");
  }

  @Test
  @DisplayName("落盘路径解析：绝对路径键被归一化为根目录内的相对键")
  void testResolveWithinNormalizesAbsoluteKey() {
    Path resolved = StoragePathGuard.resolveWithin(ROOT, "/etc/passwd");

    assertThat(resolved.startsWith(ROOT.toAbsolutePath().normalize())).isTrue();
    assertThat(resolved.getFileName().toString()).isEqualTo("passwd");
  }
}
