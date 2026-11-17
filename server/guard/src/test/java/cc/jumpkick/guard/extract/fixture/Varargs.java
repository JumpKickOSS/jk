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

    private static final Order ORDER = new Order();

    /** jhipster's MockMvc shape: a field is read between the path and the call that takes it. */
    static String callWithAField() {
        return request("/orders/{id}", ORDER.id());
    }

    /** A literal a field write ends with is no argument of the call after it. */
    static String afterAWrite() {
        last = "/orders/old";
        return request(last);
    }

    private static String last = "";

    record Order() {
        String id() {
            return "7";
        }
    }
}
