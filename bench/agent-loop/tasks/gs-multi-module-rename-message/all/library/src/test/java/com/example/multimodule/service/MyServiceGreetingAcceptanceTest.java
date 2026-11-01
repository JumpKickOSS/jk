package com.example.multimodule.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: MyService.message is renamed greeting, every caller follows. */
class MyServiceGreetingAcceptanceTest {

    @Test
    void messageIsGone() {
        assertThat(Arrays.stream(MyService.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("message");
    }

    @Test
    void greetingReturnsTheConfiguredMessage() {
        ServiceProperties properties = new ServiceProperties();
        properties.setMessage("Hello");
        assertThat(new MyService(properties).greeting()).isEqualTo("Hello");
    }
}
