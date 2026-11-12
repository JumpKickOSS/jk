package fx.carry;

import cc.jumpkick.run.ContextPropagator;

class Ok {
    static final ScopedValue<String> LABEL = ScopedValue.newInstance();

    static String label() {
        return ContextPropagator.get(LABEL);
    }
}
