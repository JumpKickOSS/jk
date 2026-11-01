package com.example.uploadingfiles.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** Hidden acceptance test for the change task: StorageService.loadAsResource is renamed openResource. */
class OpenResourceAcceptanceTests {

    @Test
    void loadAsResourceIsGone() {
        for (Class<?> type : new Class<?>[] {StorageService.class, FileSystemStorageService.class}) {
            assertThat(Arrays.stream(type.getDeclaredMethods()).map(Method::getName))
                    .doesNotContain("loadAsResource");
        }
    }

    @Test
    void openResourceReadsAStoredFile() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setLocation("target/files/" + Math.abs(new Random().nextLong()));
        StorageService service = new FileSystemStorageService(properties);
        service.init();
        service.store(new MockMultipartFile("foo", "foo.txt", MediaType.TEXT_PLAIN_VALUE, "Hello, World".getBytes()));
        assertThat(service.openResource("foo.txt").getContentAsByteArray()).isEqualTo("Hello, World".getBytes());
        assertThrows(StorageFileNotFoundException.class, () -> service.openResource("missing.txt"));
    }
}
