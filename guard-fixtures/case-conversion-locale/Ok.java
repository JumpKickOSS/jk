package fx.locale;

import java.util.Locale;

class Ok {
    String key(String raw) {
        return raw.toLowerCase(Locale.ROOT);
    }
}
