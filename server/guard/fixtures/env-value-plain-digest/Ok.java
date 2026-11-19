package fixture;

import java.util.List;

/** The same value sealed, and a constant of the manifest by digest through the literal helper. */
final class Ok {
    void token(List<String> out, String name, String value, EnvKeyText.Sealer sealer) {
        out.add("plugin-env:" + name + "=" + sealer.seal(value, EnvKeyText.credential(name)));
        out.add("profile-env:" + name + "=" + EnvKeyText.literal(value));
        String jar = "env-probe-2-" + Hashing.sha256Hex(name);
    }
}
