// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The heap flags of a forked command: read, and lowered to a lease. */
public final class HeapFlags {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    private static final Pattern XMX = Pattern.compile("^(?<prefix>-J)?-Xmx(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XMS = Pattern.compile("^(?<prefix>-J)?-Xms(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XX_HEAP = Pattern.compile(
            "^(?<prefix>-J)?-XX:(?<name>MaxHeapSize|MinHeapSize|InitialHeapSize|SoftMaxHeapSize)=(?<size>\\d+[kKmMgGtT]?)$");

    private HeapFlags() {}

    /**
     * Command line with {@code -Xmx} / {@code -Xms} / soft-max brought down to {@code xmxBytes}
     * where they were higher. A JVM command that named no heap gains an {@code -Xmx} after its
     * executable; a native-image command a {@code -J-Xmx}, the builder's.
     */
    public static List<String> rewriteHeap(List<String> command, long xmxBytes) {
        long xmx = Math.max(MIB, xmxBytes);
        List<String> out = new ArrayList<>(command.size() + 1);
        boolean sawXmx = false;
        for (String arg : command) {
            Matcher max = XMX.matcher(arg);
            Matcher xx = XX_HEAP.matcher(arg);
            Matcher min = XMS.matcher(arg);
            if (max.matches()) {
                sawXmx = true;
                out.add(prefix(max) + "-Xmx" + mib(xmx) + "m");
            } else if (xx.matches() && "MaxHeapSize".equals(xx.group("name"))) {
                sawXmx = true;
                out.add(prefix(xx) + "-XX:MaxHeapSize=" + mib(xmx) + "m");
            } else if (min.matches()) {
                long size = parseSize(min.group("size"));
                out.add(size > xmx ? prefix(min) + "-Xms" + mib(xmx) + "m" : arg);
            } else if (xx.matches()) {
                long size = parseSize(xx.group("size"));
                String name = xx.group("name");
                if (size > xmx
                        && ("MinHeapSize".equals(name)
                                || "InitialHeapSize".equals(name)
                                || "SoftMaxHeapSize".equals(name))) {
                    out.add(prefix(xx) + "-XX:" + name + "=" + mib(xmx) + "m");
                } else out.add(arg);
            } else out.add(arg);
        }
        if (!sawXmx && !out.isEmpty()) {
            int at = command.size() > 1 && setsid(command.get(0)) ? 2 : 1;
            out.add(Math.min(at, out.size()), (nativeImageCommand(command) ? "-J" : "") + "-Xmx" + mib(xmx) + "m");
        }
        return List.copyOf(out);
    }

    /** Whether {@code arg} sizes the heap: {@code -Xmx}, {@code -Xms} or an {@code -XX:} heap size. */
    public static boolean heapFlag(String arg) {
        return XMX.matcher(arg).matches()
                || XMS.matcher(arg).matches()
                || XX_HEAP.matcher(arg).matches();
    }

    /** The {@code -Xmx} the command asks for, or {@code -1} when it names none. The last flag wins. */
    public static long parseXmx(List<String> command) {
        long found = -1;
        for (String arg : command) {
            Matcher max = XMX.matcher(arg);
            if (max.matches()) {
                long size = parseSize(max.group("size"));
                if (size > 0) found = size;
                continue;
            }
            Matcher xx = XX_HEAP.matcher(arg);
            if (xx.matches() && "MaxHeapSize".equals(xx.group("name"))) {
                long size = parseSize(xx.group("size"));
                if (size > 0) found = size;
            }
        }
        return found;
    }

    static boolean jvmExecutable(String arg) {
        String name = arg;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return name.equals("java") || name.equals("javaw") || name.equals(TaskNames.NATIVE_IMAGE);
    }

    /** A HotSpot size token ({@code 512m}, {@code 1g}); {@code -1} when it is not one. */
    static long parseSize(String token) {
        if (token == null || token.isEmpty()) return -1;
        char last = token.charAt(token.length() - 1);
        long mul = 1;
        String digits = token;
        if (last == 'k' || last == 'K') {
            mul = 1024;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 'm' || last == 'M') {
            mul = MIB;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 'g' || last == 'G') {
            mul = GIB;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 't' || last == 'T') {
            mul = GIB * 1024;
            digits = token.substring(0, token.length() - 1);
        }
        try {
            return Long.parseLong(digits) * mul;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The program {@code command} execs, looking past a leading {@code setsid}. */
    static String executable(List<String> command) {
        if (command.isEmpty()) return "";
        if (command.size() > 1 && setsid(command.get(0))) return command.get(1);
        return command.get(0);
    }

    private static boolean setsid(String arg) {
        int slash = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
        String name = slash >= 0 ? arg.substring(slash + 1) : arg;
        return name.equals("setsid");
    }

    static boolean jvmCommand(List<String> command) {
        return !command.isEmpty() && jvmExecutable(executable(command));
    }

    /** Whether {@code command} runs the GraalVM {@code native-image} driver. */
    public static boolean nativeImageCommand(List<String> command) {
        String exe = executable(command);
        return jvmExecutable(exe) && exe.toLowerCase(Locale.ROOT).contains(TaskNames.NATIVE_IMAGE);
    }

    private static String prefix(Matcher matcher) {
        return matcher.group("prefix") == null ? "" : "-J";
    }

    private static long mib(long bytes) {
        return Math.max(1, bytes / MIB);
    }
}
