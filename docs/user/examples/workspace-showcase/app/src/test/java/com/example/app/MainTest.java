// SPDX-License-Identifier: Apache-2.0
package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.lib.Greet;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void greets() {
        assertEquals("hello jk", Greet.hello("jk"));
    }
}
