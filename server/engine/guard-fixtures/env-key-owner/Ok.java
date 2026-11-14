package fx.envkeyowner;

import cc.jumpkick.config.TestEnvValues;

/** A read that names a layout token, which resolves nothing from the environment. */
class Ok {
    boolean layoutToken(String name) {
        return TestEnvValues.isPathToken(name);
    }
}
