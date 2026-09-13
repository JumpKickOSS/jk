// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Os;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.build.PluginCommandExec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code avd create|list|boot} — managed AVDs under the jk SDK root ({@code ANDROID_AVD_HOME}).
 * Definitions written in avdmanager on-disk format; boot is a headless emulator whose output goes
 * to {@code <avd home>/<name>.log}.
 */
final class AvdCommand {

    private AvdCommand() {}

    static int run(PluginCommandExec exec) throws Exception {
        List<String> args = exec.args();
        Path root = exec.requireExtra("sdk-root");
        Path avdHome = root.resolve("avd");
        String sub = args.isEmpty() ? "list" : args.get(0);
        return switch (sub) {
            case "list" -> list(exec, avdHome);
            case "create" -> create(exec, root, avdHome, args);
            case "boot" -> boot(exec, root, avdHome, args);
            default -> {
                exec.out("usage: jk avd [list | create <name> --system-image <pkg> | boot <name>]");
                yield Exit.USAGE;
            }
        };
    }

    private static int list(PluginCommandExec exec, Path avdHome) throws Exception {
        if (!Files.isDirectory(avdHome)) {
            exec.out("no managed AVDs — create one with `jk avd create <name> --system-image <pkg>`");
            return 0;
        }
        try (var entries = Files.list(avdHome)) {
            var inis = entries.filter(p -> p.getFileName().toString().endsWith(".ini"))
                    .sorted()
                    .toList();
            if (inis.isEmpty()) {
                exec.out("no managed AVDs — create one with `jk avd create <name> --system-image <pkg>`");
                return 0;
            }
            for (Path ini : inis) {
                String name = ini.getFileName().toString();
                exec.out("  " + name.substring(0, name.length() - ".ini".length()));
            }
        }
        return 0;
    }

    private static int create(PluginCommandExec exec, Path root, Path avdHome, List<String> args) throws Exception {
        if (args.size() < 2) {
            exec.out("usage: jk avd create <name> --system-image <pkg>");
            return Exit.USAGE;
        }
        String name = args.get(1);
        String image = flag(args, "--system-image");
        if (image == null) {
            exec.out("jk avd create: --system-image <pkg> is required "
                    + "(e.g. system-images;android-34;aosp_atd;x86_64)");
            return Exit.USAGE;
        }
        // The image must be installed (jk android sdk provisions; licenses gate as always).
        Path imageDir = root.resolve(image.replace(';', '/'));
        if (!Files.isDirectory(imageDir)) {
            exec.out("jk avd create: system image " + image + " is not installed — " + "run `jk android sdk " + image
                    + "` first (licenses apply)");
            return 1;
        }
        String[] parts = image.split(";");
        if (parts.length < 4) {
            exec.out("jk avd create: malformed system-image package: " + image);
            return Exit.USAGE;
        }
        String target = parts[1]; // android-34
        String tag = parts[2]; // aosp_atd / default / google_apis
        String abi = parts[3]; // x86_64

        Path avdDir = Files.createDirectories(avdHome.resolve(name + ".avd"));
        StringBuilder config = new StringBuilder();
        config.append("AvdId=").append(name).append('\n');
        config.append("avd.ini.displayname=").append(name).append('\n');
        config.append("abi.type=").append(abi).append('\n');
        config.append("hw.cpu.arch=")
                .append("x86_64".equals(abi) ? "x86_64" : abi)
                .append('\n');
        config.append("image.sysdir.1=")
                .append(image.replace(';', '/'))
                .append('/')
                .append('\n');
        config.append("tag.id=").append(tag).append('\n');
        config.append("target=").append(target).append('\n');
        config.append("hw.ramSize=2048\n");
        config.append("hw.lcd.width=1080\n");
        config.append("hw.lcd.height=1920\n");
        config.append("hw.lcd.density=420\n");
        config.append("disk.dataPartition.size=2G\n");
        Files.writeString(avdDir.resolve("config.ini"), config.toString(), StandardCharsets.UTF_8);

        StringBuilder pointer = new StringBuilder();
        pointer.append("avd.ini.encoding=UTF-8\n");
        pointer.append("path=").append(avdDir.toAbsolutePath()).append('\n');
        pointer.append("target=").append(target).append('\n');
        Files.writeString(avdHome.resolve(name + ".ini"), pointer.toString(), StandardCharsets.UTF_8);
        exec.out("created AVD " + name + " (" + image + ") under " + avdHome);
        return 0;
    }

    private static int boot(PluginCommandExec exec, Path root, Path avdHome, List<String> args) throws Exception {
        if (args.size() < 2) {
            exec.out("usage: jk avd boot <name> [--emulator <path>]");
            return Exit.USAGE;
        }
        String name = args.get(1);
        if (!Files.isRegularFile(avdHome.resolve(name + ".ini"))) {
            exec.out("jk avd boot: no AVD named " + name + " — `jk avd list`");
            return 1;
        }
        // Prefer "emulator missing" over accel errors so CI/macOS (no /dev/kvm) get an actionable
        // install path. KVM is Linux-only; macOS uses Hypervisor.framework via the emulator binary.
        String override = flag(args, "--emulator");
        Path emulator = override != null ? Path.of(override) : root.resolve("emulator/emulator");
        if (!Files.isRegularFile(emulator)) {
            exec.out("jk avd boot: the emulator component is not installed — " + "run `jk android sdk emulator` first");
            return 1;
        }
        if (Os.isLinux() && !Files.exists(Path.of("/dev/kvm"))) {
            exec.out("jk avd boot: /dev/kvm is unavailable — hardware acceleration is required "
                    + "for a usable emulator");
            return 1;
        }
        exec.label("emulator " + name);
        // The emulator writes for as long as it runs, which is long after this command has
        // returned; its output goes to a log under the AVD home rather than to a pipe this
        // process would have to keep draining. One boot, one log: the follow below must not
        // find a previous boot's boot line.
        Path log = avdHome.resolve(name + ".log");
        Files.writeString(log, "", StandardCharsets.UTF_8);
        // start(), not stream(): the emulator is left running after the boot line. The argv and
        // the environment still come from the one fork owner.
        Process process = exec.tool(emulator)
                .args(List.of("-avd", name, "-no-window", "-no-audio", "-no-boot-anim"))
                .env("ANDROID_AVD_HOME", avdHome.toAbsolutePath().toString())
                .env("ANDROID_SDK_ROOT", root.toAbsolutePath().toString())
                .start(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return awaitBoot(process, log, exec::out);
    }

    /** How long the follow waits for the emulator to append to its log before looking again. */
    private static final Duration FOLLOW_POLL = Duration.ofMillis(50);

    /**
     * Follow the emulator's {@code log} up to the boot line and return; the emulator keeps running
     * and keeps appending to the log, which nothing in this process holds open. An emulator that
     * exits before booting is reaped and its status returned.
     */
    static int awaitBoot(Process process, Path log, Consumer<String> out) throws IOException, InterruptedException {
        out.accept("emulator log: " + log);
        try (InputStream in = Files.newInputStream(log)) {
            ByteArrayOutputStream partial = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (true) {
                int n = in.read(buf);
                if (n < 0) {
                    // At the end of what is written so far: more arrives while the emulator lives.
                    if (!process.isAlive()) break;
                    Thread.sleep(FOLLOW_POLL);
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') {
                        partial.write(buf[i]);
                        continue;
                    }
                    if (report(partial, out)) {
                        out.accept("emulator is up (leave it running; `adb devices` sees it)");
                        return 0;
                    }
                }
            }
            report(partial, out);
        }
        return process.waitFor();
    }

    /** Print the buffered line and empty the buffer; true when it is the boot line. */
    private static boolean report(ByteArrayOutputStream partial, Consumer<String> out) {
        String line = partial.toString(StandardCharsets.UTF_8).stripTrailing();
        partial.reset();
        if (!line.isBlank()) out.accept("  " + line);
        return line.contains("boot completed") || line.contains("Successfully loaded snapshot");
    }

    private static @Nullable String flag(List<String> args, String name) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (name.equals(args.get(i))) return args.get(i + 1);
        }
        return null;
    }
}
