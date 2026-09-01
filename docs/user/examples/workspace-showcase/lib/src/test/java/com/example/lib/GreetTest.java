// SPDX-License-Identifier: Apache-2.0
package com.example.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreetTest {

    @Test
    void greetsAName() {
        assertEquals("hello ada", Greet.hello("ada"));
    }

    @Test
    void greetsTheWorldWhenNameIsBlank() {
        assertEquals("hello world", Greet.hello("   "));
    }
}
