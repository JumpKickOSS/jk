// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

/**
 * {@code jk wrapper} — write committed {@code ./jk} + {@code jk.bat} that pin/fetch a jk version.
 * Updates springboard through the target version's own {@code --emit} so a dir-shadowed old client
 * cannot strand the wrapper.
 */
public final class WrapperCommand implements CliCommand {

    @Override
    public String name() {
        return "wrapper";
    }

    @Override
    public String description() {
        return "Write ./jk + jk.bat for pinned-jk builds";
    }

    @Override
    public List<Opt> options() {
        // No --version option: the global -V/--version flag owns that name (the dispatcher
        // rejects duplicates), so the springboard target rides as a positional.
        return List.of(Opt.flag("Write the scripts and stop (the springboard target's mode).", "--emit")
                .hide());
    }

    @Override
    public List<cc.jumpkick.model.command.Param> parameters() {
        return List.of(cc.jumpkick.model.command.Param.of(
                "version",
                cc.jumpkick.model.command.Arity.ZERO_OR_ONE,
                "Materialize <x.y.z|latest>, then write wrapper"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        Path projectDir = GlobalOptions.from(in).workingDir();
        String target = in.positionals().isEmpty() ? null : in.positionals().get(0);
        if (target == null || in.isSet("emit")) {
            emit(projectDir);
            CommandWedge.printOk(
                    "Wrapper",
                    "wrote "
                            + projectDir.resolve("jk")
                            + " and "
                            + projectDir.resolve("jk.bat")
                            + " (jk "
                            + cc.jumpkick.cli.Jk.VERSION
                            + ")");
            return 0;
        }

        // Springboard: whatever client caught this command (possibly the pinned OLD one via
        // cmd.exe shadowing) only launches the right one — it never writes newer scripts itself.
        VersionStore store = VersionStore.current();
        cc.jumpkick.http.Http http = new cc.jumpkick.http.Http();
        if ("latest".equals(target)) {
            var resp = http.get(java.net.URI.create(SelfCommand.UpdateSub.releasesBase() + "/latest/VERSION"));
            if (resp.statusCode() != 200) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Wrapper", "could not resolve the latest release (HTTP " + resp.statusCode() + ")"));
                return Exit.SOFTWARE;
            }
            target = new String(resp.body(), StandardCharsets.UTF_8).trim();
        }
        VersionStore.Materialized m = store.resolve(target).orElse(null);
        if (m == null) {
            if (target.equals(cc.jumpkick.cli.Jk.VERSION)) {
                emit(projectDir); // asked for the running version — no fetch, no exec
                CommandWedge.printOk("Wrapper", "wrote wrapper scripts (jk " + target + ")");
                return 0;
            }
            m = SelfCommand.UpdateSub.fetchAndMaterialize(
                    http, SelfCommand.UpdateSub.releasesBase(), target, store, JkStores.cas(JkDirs.cache()));
        }
        Path client = m.clientBin().orElse(null);
        if (client == null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Wrapper", "jk " + target + " is materialized without a client binary"));
            return Exit.SOFTWARE;
        }
        return new ProcessBuilder(client.toString(), "wrapper", "--emit")
                .directory(projectDir.toFile())
                .inheritIO()
                .start()
                .waitFor();
    }

    private static void emit(Path projectDir) throws IOException {
        writeTemplate("wrapper/jk.sh", projectDir.resolve("jk"), true);
        writeTemplate("wrapper/jk.bat", projectDir.resolve("jk.bat"), false);
    }

    private static void writeTemplate(String resource, Path dest, boolean executable) throws IOException {
        try (InputStream in = WrapperCommand.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("wrapper template missing from the jk binary: " + resource);
            Files.write(dest, in.readAllBytes());
        }
        if (executable) {
            try {
                var perms = Files.getPosixFilePermissions(dest);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(dest, perms);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: executability is not permission-borne.
            }
        }
    }
}
