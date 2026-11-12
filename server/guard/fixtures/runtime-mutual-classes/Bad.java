package cc.jumpkick.runtime;

// A planner and the sibling its statics were peeled into, each reaching back into the other.
final class BadPlannerArm {
    private BadPlannerArm() {}

    static String plan(String module) {
        return BadPlannerArmSteps.compile(module) + ":" + label(module);
    }

    static String label(String module) {
        return "arm:" + module;
    }
}

final class BadPlannerArmSteps {
    private BadPlannerArmSteps() {}

    static String compile(String module) {
        return "compile " + BadPlannerArm.label(module);
    }
}
