package fx.envkeyowner;

import cc.jumpkick.config.TestEnvValues;

/** A step key resolving a manifest's environment itself: a value the memo and the forecast never key. */
class Bad {
    Object stepKeyEnv(Object decls, Object mode) {
        return TestEnvValues.resolve("[env]", decls, null, null, mode);
    }
}
