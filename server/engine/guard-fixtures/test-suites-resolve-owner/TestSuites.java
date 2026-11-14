package cc.jumpkick.layout;

/** Stub of the suite resolution by its real name: a fixture slice resolves the rule's signature against it. */
public final class TestSuites {
    private TestSuites() {}

    public static Object resolve(Object selection, Object dir, boolean compact, Object declared) {
        return selection;
    }

    public static Object available(Object dir, boolean compact, Object declared) {
        return declared;
    }
}
