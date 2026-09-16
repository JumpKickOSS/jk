// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows a workspace import attributes to a module, folded before they reach the report: a row
 * whose severity and text are the same in several modules is written once, at the first module
 * saying it, with the count of modules and the module list elided past {@link #MODULES_NAMED}. A
 * row only one module says keeps its {@code [module]} prefix and nothing more; a row owned by the
 * root (an empty module path) carries no prefix.
 */
final class ModuleRows {

    /** How many modules a folded row names before eliding the rest. */
    static final int MODULES_NAMED = 3;

    private record Key(ImportReport.Severity severity, String message) {}

    private final Map<Key, List<String>> modulesByRow = new LinkedHashMap<>();

    /** Count {@code module} among those saying {@code message}. */
    void add(String module, ImportReport.Severity severity, String message) {
        List<String> modules = modulesByRow.computeIfAbsent(new Key(severity, message), k -> new ArrayList<>());
        if (!modules.contains(module)) modules.add(module);
    }

    /** Every row of {@code report}, as {@code module}'s. */
    void addAll(String module, ImportReport report) {
        for (ImportReport.Issue issue : report.issues()) add(module, issue.severity(), issue.message());
    }

    /** Write every folded row onto {@code report}, in the order the first module said each. */
    void flush(ImportReport.Builder report) {
        modulesByRow.forEach((key, modules) -> {
            String text = render(modules, key.message());
            if (key.severity() == ImportReport.Severity.ERROR) {
                report.error(text);
            } else {
                report.warning(text);
            }
        });
    }

    /** {@code [first] message (N modules: first, second, third, …)}, the count and list only when more than one module says it. */
    static String render(List<String> modules, String message) {
        List<String> named = modules.stream().filter(m -> !m.isEmpty()).toList();
        if (named.isEmpty()) return message;
        String row = "[" + named.getFirst() + "] " + message;
        if (named.size() == 1) return row;
        String list = named.size() > MODULES_NAMED
                ? String.join(", ", named.subList(0, MODULES_NAMED)) + ", …"
                : String.join(", ", named);
        return row + " (" + named.size() + " modules: " + list + ")";
    }
}
