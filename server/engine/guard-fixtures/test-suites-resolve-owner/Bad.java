package fx.testsuitesresolve;

import cc.jumpkick.layout.TestSuites;

/** A step resolving the raw session selection: a profile's every-suite switch reaches the stamp but not the run. */
class Bad {
    Object suitesToCompile(Object sessionSelection, Object dir, Object declared) {
        return TestSuites.resolve(sessionSelection, dir, false, declared);
    }
}
