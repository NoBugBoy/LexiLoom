package io.lexiloom.application.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.IoUtil;
import io.lexiloom.application.common.storage.adapter.RustfsStorageAdapter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RustfsStorageAdapterTest {

  private RustfsStorageAdapter adapter;
  private RustfsStorageProperties properties;

  @BeforeEach
  void setUp() {
    properties = new RustfsStorageProperties();
    properties.setEnabled(true);
    properties.setEndpoint("http://localhost:9000");
    properties.setBucketName("test-bucket");
    adapter = new RustfsStorageAdapter(properties);
  }

  @AfterEach
  void tearDown() {
    adapter.delete("docs/test.txt");
  }

  @Test
  @DisplayName("rustfs 上传、查询、下载与删除全流程验证")
  void testRustfsLifecycle() {
    String content = "Hello rustfs high performance storage";
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);

    String key = "docs/test.txt";
    String url = adapter.upload(key, stream, "text/plain", bytes.length);

    assertThat(url).contains("http://localhost:9000/test-bucket/docs/test.txt");
    assertThat(adapter.exists(key)).isTrue();

    InputStream downloadStream = adapter.download(key);
    String readContent = IoUtil.read(downloadStream, StandardCharsets.UTF_8);
    assertThat(readContent).isEqualTo(content);

    adapter.delete(key);
    assertThat(adapter.exists(key)).isFalse();
  }
}
