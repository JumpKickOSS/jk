package fx.scoped;

import cc.jumpkick.run.ContextPropagator;

class Ok {
    private static final ScopedValue<String> LABEL = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(LABEL);
    }
}
