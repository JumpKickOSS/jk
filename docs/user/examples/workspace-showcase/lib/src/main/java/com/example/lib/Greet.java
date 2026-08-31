// SPDX-License-Identifier: Apache-2.0
package com.example.lib;

/** Greeting text, shared with every module that depends on {@code lib}. */
public final class Greet {

    private Greet() {}

    /** Greets {@code name}, or the world when {@code name} is blank. */
    public static String hello(String name) {
        return "hello " + (name.isBlank() ? "world" : name);
    }
}
