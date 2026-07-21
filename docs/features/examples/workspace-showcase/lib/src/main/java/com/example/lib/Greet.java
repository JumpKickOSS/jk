// SPDX-License-Identifier: Apache-2.0
package com.example.lib;

/** Tiny library shared by the showcase app module. */
public final class Greet {
    public static String hello(String name) {
        return "hello " + (name == null || name.isBlank() ? "world" : name);
    }

    private Greet() {}
}
