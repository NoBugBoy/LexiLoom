package io.lexiloom.application;

import static io.restassured.RestAssured.given;

import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseControllerIntegrationTest {

  protected static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("lexiloom_test")
          .withUsername("test")
          .withPassword("test");

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            String.format(
                "jdbc:postgresql://127.0.0.1:%d/%s?sslmode=disable",
                postgres.getMappedPort(5432), postgres.getDatabaseName()));
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
  }

  @LocalServerPort protected int port;

  @Autowired protected RedisTokenManager redisTokenManager;

  @MockitoBean protected JavaMailSender javaMailSender;

  @BeforeEach
  void setUpRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
    org.mockito.Mockito.lenient()
        .when(javaMailSender.createMimeMessage())
        .thenReturn(new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
  }

  protected String generateUserToken(Long userId) {
    return redisTokenManager.generateToken(userId, java.util.List.of());
  }

  protected String generateAdminToken(Long adminId) {
    return generateAdminToken(adminId, List.of(AdminRoleEnum.ADMIN));
  }

  protected String generateAdminToken(Long adminId, List<AdminRoleEnum> roles) {
    return redisTokenManager.generateToken(adminId, roles);
  }

  protected RequestSpecification givenAdmin() {
    return givenAdmin(1L);
  }

  protected RequestSpecification givenAdmin(Long adminId) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + generateAdminToken(adminId));
  }

  protected RequestSpecification givenAdmin(Long adminId, List<AdminRoleEnum> roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + generateAdminToken(adminId, roles));
  }

  protected RequestSpecification givenUser(Long userId) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + generateUserToken(userId));
  }

  protected RequestSpecification givenAnonymous() {
    return given().contentType(ContentType.JSON);
  }
}
