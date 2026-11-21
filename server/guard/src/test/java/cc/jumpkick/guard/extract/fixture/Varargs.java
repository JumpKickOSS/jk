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

    /** A literal returned on one branch is no argument of the call on the other. */
    static String afterABranch(boolean early) {
        if (early) return "/orders/early";
        return request(last);
    }

    /** Both arms of a conditional argument, and a literal ahead of a condition, are the call's. */
    static String eitherArm(boolean a, boolean b) {
        return request(a ? "/orders/a" : "/orders/b", a && b);
    }

    /** A concatenation's constant parts are arguments of the call it feeds. */
    static String concatenated(int id) {
        return request("/orders/" + id + "/lines");
    }

    record Order() {
        String id() {
            return "7";
        }
    }
}
