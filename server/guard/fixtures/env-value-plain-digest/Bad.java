package fixture;

import java.util.List;

/** A step key taking a value the caller's shell answered by plain digest: a guess away from it. */
final class Bad {
    void token(List<String> out, String name, String value) {
        out.add("plugin-env:" + name + "=" + Hashing.sha256Hex(value));
    }
}
