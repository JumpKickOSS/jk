// SPDX-License-Identifier: Apache-2.0
package com.example.app;

import com.example.lib.Greet;

/** Prints the greeting built by the {@code lib} module. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.out.println(greeting(args));
    }

    /** The line {@code main} prints: the first argument greeted by {@code lib}. */
    static String greeting(String[] args) {
        return Greet.hello(args.length > 0 ? args[0] : "");
    }
}
