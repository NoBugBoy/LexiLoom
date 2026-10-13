package com.knowflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.events.jpa.updating.DefaultJpaEventPublication;
import org.springframework.transaction.support.TransactionTemplate;

class ModulithJpaEventPublicationTest extends BaseControllerIntegrationTest {

  @Autowired private EntityManager entityManager;

  @Autowired private EventPublicationRepository eventPublicationRepository;

  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("DefaultJpaEventPublication 必须作为受管实体成功注册到当前 JPA PersistenceUnit")
  void shouldRegisterDefaultJpaEventPublicationInPersistenceUnit() {
    assertThat(entityManager.getMetamodel().getEntities())
        .extracting("bindableJavaType")
        .contains(DefaultJpaEventPublication.class);

    assertThatCode(() -> entityManager.getMetamodel().managedType(DefaultJpaEventPublication.class))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("EventPublicationRepository 能够正常持久化与查询事件发布记录，不抛出 Unknown entity type 异常")
  void shouldPersistAndQueryEventPublicationsSuccessfully() {
    record TestDomainEvent(String eventId, String message) {}

    TestDomainEvent event = new TestDomainEvent("evt-001", "Testing Modulith JPA Publication");
    PublicationTargetIdentifier listenerId = PublicationTargetIdentifier.of("testListener");

    TargetEventPublication publication = TargetEventPublication.of(event, listenerId);

    transactionTemplate.executeWithoutResult(
        status -> {
          TargetEventPublication created = eventPublicationRepository.create(publication);
          assertThat(created).isNotNull();
        });

    var incomplete = eventPublicationRepository.findIncompletePublications();
    assertThat(incomplete)
        .isNotEmpty()
        .anyMatch(pub -> pub.getTargetIdentifier().equals(listenerId));
  }

  @Test
  @DisplayName("event_publication.serialized_event 必须成功自愈升级为 text 大文本类型")
  void shouldHaveTextTypeForSerializedEvent() {
    java.util.List<Object[]> cols =
        entityManager
            .createNativeQuery(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name ="
                    + " 'event_publication' AND column_name = 'serialized_event'")
            .getResultList();

    assertThat(cols).isNotEmpty();
    assertThat(cols.get(0)[1]).isEqualTo("text");
  }

  @Test
  @DisplayName("发布超过 255 字符的大体积领域事件能够成功持久化，不抛出任何长度溢出异常")
  void shouldPersistLargeEventPublicationWithoutLengthOverflow() {
    record LargeDomainEvent(String eventId, String largePayload) {}

    String bigText = "A".repeat(1000);
    LargeDomainEvent event = new LargeDomainEvent("large-evt-001", bigText);
    PublicationTargetIdentifier listenerId =
        PublicationTargetIdentifier.of(
            "com.knowflow.application.ai.listener.DocVectorSyncListener.onDocumentStateTransition");

    TargetEventPublication publication = TargetEventPublication.of(event, listenerId);

    transactionTemplate.executeWithoutResult(
        status -> {
          TargetEventPublication created = eventPublicationRepository.create(publication);
          assertThat(created).isNotNull();
        });
  }
}
