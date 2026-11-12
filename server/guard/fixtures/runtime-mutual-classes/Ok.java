package cc.jumpkick.runtime;

// The same split with the edge one way: the steps take what they need as an argument.
final class OkPlannerArm {
    private OkPlannerArm() {}

    static String plan(String module) {
        return OkPlannerArmSteps.compile(label(module));
    }

    static String label(String module) {
        return "arm:" + module;
    }
}

final class OkPlannerArmSteps {
    private OkPlannerArmSteps() {}

    static String compile(String label) {
        return "compile " + label;
    }
}
