package fx.testsuitesresolve;

import cc.jumpkick.layout.TestSuites;

/** Listing the suites a module has decides nothing about what a run runs. */
class Ok {
    Object suiteNames(Object dir, Object declared) {
        return TestSuites.available(dir, false, declared);
    }
}
