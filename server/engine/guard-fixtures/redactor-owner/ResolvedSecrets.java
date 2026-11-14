package cc.jumpkick.config;

import java.nio.file.Path;

/** Stub of the filed-credential table by its real name: a fixture slice resolves the rule's signature against it. */
public final class ResolvedSecrets {
    private ResolvedSecrets() {}

    public static SecretRedactor plus(Path dir, SecretRedactor base) {
        return base;
    }
}
