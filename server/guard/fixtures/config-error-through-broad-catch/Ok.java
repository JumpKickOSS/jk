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

    static String wrappedOrNull(Path manifest) {
        try {
            return load(manifest);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    static String load(Path manifest) throws IOException {
        return JkBuildParser.parse(manifest).strip();
    }
}

final class JkBuildParser {
    /**
     * The project's name.
     *
     * @throws UserConfigParseException when the user's config.toml does not parse
     */
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
