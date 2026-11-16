// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

/** A varargs call whose leading literal is loaded before its array is allocated. */
public final class Varargs {

    private Varargs() {}

    static String request(String path, Object... vars) {
        return path + vars.length;
    }

    static String call() {
        return request("/orders/{id}", 7);
    }
}
