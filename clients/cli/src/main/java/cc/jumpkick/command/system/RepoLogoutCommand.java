// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackendException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk repo logout <id>} — forget stored credentials for an artifact repository: the keychain
 * item that holds the secret, then the record. Leaves env vars and {@code ~/.m2/settings.xml}
 * untouched.
 */
public final class RepoLogoutCommand implements CliCommand {

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "Remove stored credentials for an artifact repository";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
                        "<dir>", "Override the credentials directory. Default: <home>/creds/repo.", "--credentials-dir")
                .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("id", Arity.ONE, "Repository id (matches [repositories.<id>] in jk.toml)."));
    }

    @Override
    public int run(Invocation in) {
        String id = in.positionals().get(0);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        RepoCredentialStore store =
                credentialsDir != null ? new RepoCredentialStore(credentialsDir) : new RepoCredentialStore();
        try {
            store.clear(id);
        } catch (SecretBackendException refused) {
            CliOutput.err("error: " + refused.getMessage() + "; the login is kept, so `jk repo logout " + id
                    + "` can be run again once the keychain answers.");
            return 1;
        }
        if (!global.quiet) {
            CommandWedge.printOk("Logout", "Removed stored credentials for repository '" + id + "'.");
        }
        return 0;
    }
}
