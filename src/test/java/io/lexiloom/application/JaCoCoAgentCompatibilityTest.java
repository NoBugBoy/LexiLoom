package io.lexiloom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the agent configured by the Maven test lifecycle. Run with {@code ./mvnw
 * -Dtest=JaCoCoAgentCompatibilityTest test}; JDK 25 also exercises Java 25 bytecode.
 */
@DisplayName("JaCoCo agent compatibility with the configured Java targets and class filters")
class JaCoCoAgentCompatibilityTest {

  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "Java {0} bytecode")
  @ValueSource(ints = {21, 25})
  @DisplayName("Records normal branches and exceptional exits without changing their behavior")
  void instrumentsSupportedBytecode(int release) throws Exception {
    assumeTrue(Runtime.version().feature() >= release, "Requires JDK " + release + " or newer");
    String className = "io.lexiloom.coverage.Java" + release + "Fixture";
    compileFixture(className, release);

    try (var loader = fixtureClassLoader()) {
      Class<?> fixture = loader.loadClass(className);
      Method classify = fixture.getMethod("classify", int.class);

      // Transformation failures can leave a class runnable but without coverage. Check the probes,
      // rather than just checking that the class loads. This also avoids a JaCoCo test dependency.
      Method initializer =
          fixture.getDeclaredMethod(
              "$jacocoInit", MethodHandles.Lookup.class, String.class, Class.class);
      initializer.setAccessible(true);
      boolean[] probes =
          (boolean[])
              initializer.invoke(
                  null,
                  MethodHandles.privateLookupIn(fixture, MethodHandles.lookup()),
                  "$jacocoData",
                  boolean[].class);
      assertThat(probes).isNotEmpty().containsOnly(false);

      assertThat(classify.invoke(null, 0)).isEqualTo("zero");
      boolean[] afterZero = probes.clone();
      assertThat(afterZero).contains(true);

      assertThat(classify.invoke(null, Integer.MAX_VALUE)).isEqualTo("positive");
      boolean[] afterPositive = probes.clone();
      assertThat(afterPositive).isNotEqualTo(afterZero);

      assertThatThrownBy(() -> classify.invoke(null, Integer.MIN_VALUE))
          .hasRootCauseInstanceOf(IllegalArgumentException.class)
          .hasRootCauseMessage("negative");
      assertThat(probes).isNotEqualTo(afterPositive);
      assertThat(fixture.getField("calls").getInt(null)).isEqualTo(3);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CoverageMockitoMockFixture",
        "CoverageByteBuddyFixture",
        "CoverageHibernateProxyFixture",
        "CoverageHibernateInstantiatorFixture"
      })
  @DisplayName("Keeps generated mock and persistence classes excluded from instrumentation")
  void doesNotInstrumentExcludedClasses(String simpleName) throws Exception {
    assertNotInstrumented("io.lexiloom.coverage." + simpleName);
  }

  @Test
  @DisplayName("Keeps classes outside the application namespace excluded from instrumentation")
  void doesNotInstrumentThirdPartyClasses() throws Exception {
    assertNotInstrumented("thirdparty.coverage.CoverageFixture");
  }

  private void assertNotInstrumented(String className) throws Exception {
    compileFixture(className, 21);

    try (var loader = fixtureClassLoader()) {
      Class<?> fixture = loader.loadClass(className);

      assertThat(fixture.getMethod("classify", int.class).invoke(null, 0)).isEqualTo("zero");
      assertThat(fixture.getDeclaredMethods())
          .extracting(Method::getName)
          .doesNotContain("$jacocoInit");
    }
  }

  private URLClassLoader fixtureClassLoader() throws Exception {
    return new URLClassLoader(
        new URL[] {temporaryDirectory.toUri().toURL()}, getClass().getClassLoader());
  }

  private void compileFixture(String className, int release) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("These coverage compatibility tests require a JDK").isNotNull();
    int separator = className.lastIndexOf('.');
    String packageName = className.substring(0, separator);
    String simpleName = className.substring(separator + 1);
    Path source = temporaryDirectory.resolve(className.replace('.', '/') + ".java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
        package %s;
        public class %s {
          public static int calls;
          public static String classify(int value) {
            try {
              if (value < 0) {
                throw new IllegalArgumentException("negative");
              }
              return switch (value) {
                case 0 -> "zero";
                default -> "positive";
              };
            } finally {
              calls++;
            }
          }
        }
        """
            .formatted(packageName, simpleName));

    var diagnostics = new StringWriter();
    try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
      var task =
          compiler.getTask(
              diagnostics,
              fileManager,
              null,
              List.of(
                  "--release",
                  Integer.toString(release),
                  "-proc:none",
                  "-d",
                  temporaryDirectory.toString()),
              null,
              fileManager.getJavaFileObjects(source));
      assertThat(task.call()).as("Fixture compilation: %s", diagnostics).isTrue();
    }
  }
}
