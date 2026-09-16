package com.acme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreetingControllerTest {

    @Test
    void greets_by_name() {
        var body = new GreetingController().getGreeting("Ada").getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Hello, Ada!");
    }
}
