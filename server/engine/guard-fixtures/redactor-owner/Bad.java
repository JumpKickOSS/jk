package fx.redactorowner;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import java.nio.file.Path;

/** A report sink composing its own redactor: the filed credentials without the module's .env names. */
class Bad {
    String mask(Path dir, String line) {
        return ResolvedSecrets.plus(dir, SecretRedactor.none()).redact(line);
    }
}
