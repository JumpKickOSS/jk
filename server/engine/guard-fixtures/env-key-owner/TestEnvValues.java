package cc.jumpkick.config;

/** Stub of the expansion by its real name: a fixture slice resolves the rule's signature against it. */
public final class TestEnvValues {
    private TestEnvValues() {}

    public static Object resolve(Object at, Object declared, Object moduleDir, Object target, Object mode) {
        return declared;
    }

    public static boolean isPathToken(String name) {
        return false;
    }
}
