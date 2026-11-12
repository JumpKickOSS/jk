package cc.jumpkick.layout;

/** Stub of the language read by its real name: a fixture slice resolves the rule's signature against it. */
public record Languages(boolean java, boolean kotlin) {
    public static Languages resolve(Object project, Object dir) {
        return new Languages(true, false);
    }

    public static boolean anySourceUnder(Object dir, String suffix) {
        return false;
    }
}
