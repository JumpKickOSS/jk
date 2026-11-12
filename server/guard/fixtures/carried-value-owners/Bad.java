package fx.carry;

import cc.jumpkick.run.ContextPropagator;

class Bad {
    static final ScopedValue<String> LABEL = ScopedValue.newInstance();

    static void register() {
        ContextPropagator.carry(LABEL);
    }
}
