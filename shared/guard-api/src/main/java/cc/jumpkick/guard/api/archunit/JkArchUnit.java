// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import cc.jumpkick.guard.api.ToolSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.guard.facts.Fingerprints;
import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.ViolationHandler;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An ArchUnit rule's violations as guard violations. Each failure line becomes a
 * {@link ToolSite} whose fingerprint is the line with source line numbers and synthetic ordinals
 * folded away — the same normalisation {@code FreezingArchRule}'s store applies, so a violation
 * reformatted or moved down the file keeps its baseline entry. Accesses ({@code JavaAccess}) also
 * carry the origin's file and line for the diagnostic.
 */
public final class JkArchUnit {

    private static final Pattern LINE_NUMBER = Pattern.compile(":\\d+\\)");

    private JkArchUnit() {}

    /**
     * Point {@code FreezingArchRule} at the baseline: the store reads {@code jk-guards-baseline.toml}
     * for the running guard and the line matcher compares with jk's normalisation. Call once, before
     * the first {@code freeze(...)}; the same two properties in {@code archunit.properties} do the same.
     */
    public static void configureFreezing() {
        ArchConfiguration.get().setProperty("freeze.store", JkViolationStore.class.getName());
        ArchConfiguration.get().setProperty("freeze.lineMatcher", JkLineMatcher.class.getName());
    }

    /** Evaluate {@code rule} over {@code classes} and report every violation to {@code v}. */
    public static void check(ArchRule rule, JavaClasses classes, Violations v) {
        report(rule.evaluate(classes), v);
    }

    /** A consumer for results produced elsewhere: {@code rule.evaluate(classes)} then this. */
    public static Consumer<EvaluationResult> reporter(Violations v) {
        return result -> report(result, v);
    }

    /** Report an evaluation's violations. */
    public static void report(EvaluationResult result, Violations v) {
        // Accesses first: they know their file and line. Then every other violating object, by message.
        Map<String, ToolSite> sites = new LinkedHashMap<>();
        result.handleViolations(new ViolationHandler<JavaAccess<?>>() {
            @Override
            public void handle(Collection<JavaAccess<?>> violating, String message) {
                JavaAccess<?> first = violating.iterator().next();
                JavaClass owner = first.getOriginOwner();
                String file = sourceFile(owner);
                sites.putIfAbsent(normalise(message), new ToolSite(normalise(message), file, first.getLineNumber()));
            }
        });
        result.handleViolations(new ViolationHandler<Object>() {
            @Override
            public void handle(Collection<Object> violating, String message) {
                String key = normalise(message);
                if (sites.containsKey(key)) return;
                String file = null;
                for (Object o : violating) {
                    if (o instanceof JavaClass c) {
                        file = sourceFile(c);
                        break;
                    }
                }
                sites.put(key, new ToolSite(key, file, 0));
            }
        });
        for (String detail : result.getFailureReport().getDetails()) {
            String key = normalise(detail);
            ToolSite site = sites.getOrDefault(key, new ToolSite(key, null, 0));
            v.add(site, detail);
        }
    }

    /** ArchUnit's message with {@code (File.java:123)} reduced to {@code (File.java)} and synthetic ordinals folded. */
    public static String normalise(String message) {
        return Fingerprints.normalise(LINE_NUMBER.matcher(message).replaceAll(")"));
    }

    static @Nullable String sourceFile(JavaClass c) {
        String pkg = c.getPackageName().replace('.', '/');
        String name = c.getSourceCodeLocation().getSourceFileName();
        if (name == null || name.isEmpty()) return null;
        return pkg.isEmpty() ? name : pkg + "/" + name;
    }
}
