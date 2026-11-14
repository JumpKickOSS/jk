package fx.redactorowner;

import cc.jumpkick.config.BuildEnv;
import java.nio.file.Path;

/** A report sink writing through the module's one redactor, the wire's. */
class Ok {
    String mask(Path dir, String line) {
        return BuildEnv.secretsFor(dir).redact(line);
    }
}
