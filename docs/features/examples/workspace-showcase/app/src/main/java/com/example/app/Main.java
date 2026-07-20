// SPDX-License-Identifier: Apache-2.0
package com.example.app;

import com.example.lib.Greet;

/** Showcase app: depends on the workspace lib module. */
public final class Main {
    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "jk";
        System.out.println(Greet.hello(name));
    }

    private Main() {}
}
