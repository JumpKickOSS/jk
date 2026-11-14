package cc.jumpkick.config;

import java.nio.file.Path;

/** Stub of the redactor's owner by its real name. */
public final class BuildEnv {
    private BuildEnv() {}

    public static SecretRedactor secretsFor(Path dir) {
        return ResolvedSecrets.plus(dir, SecretRedactor.from(dir));
    }
}
