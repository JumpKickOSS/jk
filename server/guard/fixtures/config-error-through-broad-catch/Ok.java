package fx.config.ok;

import java.io.IOException;
import java.nio.file.Path;

class Ok {
    static String nameOrNull(Path manifest) {
        try {
            return JkBuildParser.parse(manifest);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (Exception e) {
            return null;
        }
    }

    static String nameOrWhy(Path manifest) {
        try {
            return JkBuildParser.parse(manifest);
        } catch (Exception e) {
            return Errors.text(e);
        }
    }
}

final class JkBuildParser {
    static String parse(Path manifest) throws IOException {
        return manifest.toString();
    }
}

final class UserConfigParseException extends RuntimeException {}

final class Errors {
    static String text(Throwable t) {
        return String.valueOf(t.getMessage());
    }
}
