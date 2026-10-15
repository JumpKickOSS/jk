package com.acme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GreetingTest {
    @Test
    void greets_through_a_javafx_property() {
        Greeting g = new Greeting();
        g.textProperty().set("Hi");
        assertEquals("Hi, jk", g.greet("jk"));
    }

    @Test
    void the_javafx_modules_are_named_modules() {
        assertTrue(ModuleLayer.boot().findModule("javafx.base").isPresent());
        assertTrue(ModuleLayer.boot().findModule("javafx.controls").isPresent());
    }
}
