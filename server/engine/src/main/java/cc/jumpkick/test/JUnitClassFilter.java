// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Class-name filter for {@link JUnitLauncher} {@code --affected} runs. */
final class JUnitClassFilter {

    private JUnitClassFilter() {}

    static List<String> singleWorkerArgs(Path testClassesDir, List<String> classNames) {
        List<String> args = new ArrayList<>();
        args.add("--scan-classpath=" + testClassesDir);
        if (classNames == null || classNames.isEmpty()) return args;
        StringBuilder re = new StringBuilder("^(");
        for (int i = 0; i < classNames.size(); i++) {
            if (i > 0) re.append('|');
            re.append(Pattern.quote(classNames.get(i)));
        }
        re.append(")$");
        args.add("--filter=" + re);
        return args;
    }
}
