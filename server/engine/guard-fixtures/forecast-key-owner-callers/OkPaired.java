package fx.keyparity.callers;

import cc.jumpkick.run.StepForecast;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import java.util.List;

/**
 * Owners both sides reach: a build step and a forecast method; a predictor lambda; a forecast
 * entry point in the owner's own class, counted for the side that calls it; and an owner reached
 * only through a shared input owner, counted for the sides that call that one.
 */
class OkPaired {

    @KeyOwner(step = "package-jar")
    static String jarKey(String jar) {
        return ActionKey.forArtifact("package-jar:" + jar, "1", List.of());
    }

    @KeyOwner(step = "package-assembly")
    static String assemblyKey(String jar) {
        return ActionKey.forArtifact("package-assembly:" + jar, "1", List.of());
    }

    static boolean assemblyCached(String jar) {
        return assemblyKey(jar).isEmpty();
    }

    @KeyOwner(step = "package-war", unpaired = "explain does not forecast the war at all")
    static String warKey(String war) {
        return ActionKey.forArtifact("package-war:" + war, "1", List.of());
    }

    @KeyOwner(step = "package-lib-dir")
    static String libKey(String lib) {
        return ActionKey.forArtifact("package-lib-dir:" + lib, "1", List.of());
    }
}

final class LibInputs {
    @KeyOwner(step = "package-lib-dir")
    static String lib(String lib) {
        return OkPaired.libKey(lib.strip());
    }
}

final class PairedPlan {
    static String jarStep(String jar) {
        return OkPaired.jarKey(jar);
    }

    static String assemblyStep(String jar) {
        return OkPaired.assemblyKey(jar);
    }

    static String warStep(String war) {
        return OkPaired.warKey(war);
    }

    static String libStep(String lib) {
        return LibInputs.lib(lib);
    }

    static StepForecast jarPredictor(String jar) {
        return StepForecast.priced(scope -> {
            OkPaired.jarKey(jar);
            return null;
        });
    }
}

final class PairedTailForecasts {
    static boolean assembly(String jar) {
        return OkPaired.assemblyCached(jar);
    }

    static String lib(String lib) {
        return LibInputs.lib(lib);
    }
}
