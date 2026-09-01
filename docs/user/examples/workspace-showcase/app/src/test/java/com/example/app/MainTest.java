// SPDX-License-Identifier: Apache-2.0
package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Asserts that {@code app} really is compiled and run against the sibling {@code lib}. */
class MainTest {

    @Test
    void greetsTheFirstArgument() {
        assertEquals("hello ada", Main.greeting(new String[] {"ada"}));
    }

    @Test
    void greetsTheWorldWithoutArguments() {
        assertEquals("hello world", Main.greeting(new String[0]));
    }
}
