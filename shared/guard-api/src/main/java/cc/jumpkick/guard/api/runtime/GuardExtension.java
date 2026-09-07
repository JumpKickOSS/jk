// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Allow;
import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Output;
import cc.jumpkick.guard.api.OwnerMissing;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.Violations;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * The runtime behind {@link GuardSuite}, registered as a JUnit extension service and switched on by
 * the launcher ({@code junit.jupiter.extensions.autodetection.enabled}): resolves {@link Facts}, {@link Model}, {@link Text},
 * {@link Output} and {@link Violations} parameters from the views jk hands the forked JVM, collects
 * what each {@link Guard} reports, and appends one report line per guard for the engine. A guard
 * that throws is reported as such and the next guard still runs; a guard's violations never fail
 * the JUnit run — the verdict is the engine's.
 */
public final class GuardExtension
        implements ParameterResolver,
                ExecutionCondition,
                BeforeEachCallback,
                AfterEachCallback,
                TestExecutionExceptionHandler {

    /** Outside jk there are no views to inject: the guards are skipped, not failed. */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext ctx) {
        if (ctx.getTestMethod().isEmpty()) return ConditionEvaluationResult.enabled("class");
        if (ctx.getRequiredTestMethod().getAnnotation(Guard.class) == null)
            return ConditionEvaluationResult.enabled("not a guard");
        return GuardRuntime.current() != null
                ? ConditionEvaluationResult.enabled("jk configured the views")
                : ConditionEvaluationResult.disabled(
                        "guard tests run under jk (jk build / jk guard), which hands the suite its views");
    }

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(GuardExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext p, ExtensionContext ctx) {
        Class<?> t = p.getParameter().getType();
        return t == Facts.class || t == Model.class || t == Text.class || t == Output.class || t == Violations.class;
    }

    @Override
    public Object resolveParameter(ParameterContext p, ExtensionContext ctx) {
        Class<?> t = p.getParameter().getType();
        if (t == Violations.class) return collector(ctx);
        GuardRuntime rt = GuardRuntime.current();
        if (rt == null) {
            throw new ParameterResolutionException(
                    "guard suites run under jk (jk build / jk guard), which hands the suite its " + t.getSimpleName()
                            + " view; there is no jk.guard.config here");
        }
        if (t == Facts.class) return rt.facts();
        if (t == Model.class) return rt.model();
        if (t == Text.class) return rt.text();
        return rt.output();
    }

    private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

    /** Run {@code body} as guard {@code id} on this thread — what the extension does around a @Guard method. */
    public static void asGuard(String id, Runnable body) {
        String before = CURRENT.get();
        CURRENT.set(id);
        try {
            body.run();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }

    /** The id of the guard running on this thread, for stores that key on it; {@code null} between guards. */
    public static @Nullable String currentGuardId() {
        return CURRENT.get();
    }

    @Override
    public void beforeEach(ExtensionContext ctx) {
        ctx.getStore(NS).put("collector", new Report.Collector());
        Guard g = ctx.getRequiredTestMethod().getAnnotation(Guard.class);
        CURRENT.set(g == null ? null : g.id());
    }

    @Override
    public void handleTestExecutionException(ExtensionContext ctx, Throwable t) {
        Report.Collector c = collector(ctx);
        if (t instanceof OwnerMissing) c.ownerMissing(t.getMessage() == null ? "owner missing" : t.getMessage());
        else c.threw(t);
        // swallowed on purpose: the engine reports scanner-failed under the guard's id
    }

    @Override
    public void afterEach(ExtensionContext ctx) throws Exception {
        CURRENT.remove();
        Method m = ctx.getRequiredTestMethod();
        Guard g = m.getAnnotation(Guard.class);
        if (g == null) return;
        GuardSuite suite = ctx.getRequiredTestClass().getAnnotation(GuardSuite.class);
        List<Report.AllowEntry> allows = new ArrayList<>();
        for (Allow a : m.getAnnotationsByType(Allow.class)) allows.add(new Report.AllowEntry(a.in(), a.reason()));
        Fixture fx = m.getAnnotation(Fixture.class);
        List<String> params = new ArrayList<>();
        for (Class<?> pt : m.getParameterTypes()) params.add(pt.getSimpleName());
        Report.Line line = new Report.Line(
                g.id(),
                g.why(),
                g.instead(),
                ctx.getRequiredTestClass().getName() + "#" + m.getName(),
                suite == null ? Scope.MODULE : suite.scope(),
                allows,
                fx == null ? null : fx.value(),
                params,
                collector(ctx));
        GuardRuntime rt = GuardRuntime.current();
        if (rt != null) Report.append(rt.reportFile(), line);
        else System.err.println("jk guard: " + line.toJson());
    }

    private static Report.Collector collector(ExtensionContext ctx) {
        Report.Collector c = ctx.getStore(NS).get("collector", Report.Collector.class);
        return c == null ? fresh(ctx) : c;
    }

    private static Report.Collector fresh(ExtensionContext ctx) {
        Report.Collector c = new Report.Collector();
        ctx.getStore(NS).put("collector", c);
        return c;
    }

    /** For tests of the extension itself: the last runtime error, if construction failed. */
    public static @Nullable String runtimeProblem() {
        return GuardRuntime.problem();
    }
}
