// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

/**
 * {@code jk wrapper} — write committed {@code ./jk} + {@code jk.bat} bootstrap scripts. The
 * wrapper bootstraps current jk (honoring the lock's optional {@code jk-min} floor); it never
 * pins a version — the lock pins inputs, not the operator. {@code jk wrapper update} refreshes
 * the scripts from the running binary.
 */
public final class WrapperCommand implements CliCommand {

    @Override
    public String name() {
        return "wrapper";
    }

    @Override
    public String description() {
        return "Write ./jk + jk.bat bootstrap scripts";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("update", Arity.ZERO_OR_ONE, "`update` refreshes the committed scripts"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        if (!in.positionals().isEmpty() && !"update".equals(in.positionals().get(0))) {
            CommandWedge.printFail(
                    "Wrapper",
                    "the wrapper is a bootstrapper, not a pin — it has no version argument; align teams via"
                            + " the installer or CI images, and use `jk self update` for the tool itself");
            return Exit.USAGE;
        }
        Path projectDir = GlobalOptions.from(in).workingDir();
        emit(projectDir);
        CommandWedge.printOk(
                "Wrapper",
                "wrote " + projectDir.resolve("jk") + " and " + projectDir.resolve("jk.bat")
                        + " (bootstraps current jk; honors the lock's jk-min floor)");
        return 0;
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
