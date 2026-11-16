// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.host.GitConfigFiles;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;

/**
 * JGit reads git's configuration as the {@code git} command does under the same environment, so
 * the two backends of {@link GitBackend} answer alike: {@code GIT_CONFIG_GLOBAL} names the user's
 * configuration file in place of {@code ~/.gitconfig} and {@code ~/.config/git/config}, as
 * git 2.32 and later read it, and {@code GIT_CONFIG_NOSYSTEM} drops the system file ({@link
 * GitConfigFiles}). JGit reads {@code ~/.gitconfig} whatever the environment says, so a process
 * that pointed git at another configuration (jk's test JVMs point it into their sandbox) still had
 * JGit read the developer's identity, signing and URL rewrites.
 *
 * <p>Installed once per process, before the first repository JGit opens; with neither variable set
 * it reads what JGit's own reader reads.
 */
public final class JGitEnvironment {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private JGitEnvironment() {}

    /** Wrap the process's JGit reader, once. */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        SystemReader.setInstance(new Reader(SystemReader.getInstance()));
    }

    /** JGit's reader with git's configuration files taken from {@link GitConfigFiles}. */
    static final class Reader extends SystemReader.Delegate {

        Reader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(@Nullable Config parent, FS fs) {
            Path named = named();
            return named == null ? super.openUserConfig(parent, fs) : new FileBasedConfig(parent, named.toFile(), fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(@Nullable Config parent, FS fs) {
            if (!noSystem()) return super.openSystemConfig(parent, fs);
            return new FileBasedConfig(parent, null, fs) {
                @Override
                public void load() {
                    // No system file, as GIT_CONFIG_NOSYSTEM says.
                }

                @Override
                public boolean isOutdated() {
                    return false;
                }
            };
        }

        /**
         * Under {@code GIT_CONFIG_GLOBAL}, the directory holding that file stands for the user's
         * configuration home ({@code ~/.config}): JGit reads its default excludes ({@code
         * git/ignore}) and its own settings ({@code jgit/config}) there, never from the user's.
         */
        @Override
        public @Nullable Path getXdgConfigDirectory(FS fs) {
            Path named = named();
            Path dir = named == null ? null : named.toAbsolutePath().getParent();
            return dir == null ? super.getXdgConfigDirectory(fs) : dir;
        }

        /** The file {@code GIT_CONFIG_GLOBAL} names in this reader's environment, or its property. */
        @Nullable
        Path named() {
            String property = System.getProperty(GitConfigFiles.GLOBAL_PROPERTY);
            String value = property != null && !property.isBlank() ? property : getenv("GIT_CONFIG_GLOBAL");
            return value == null || value.isBlank() ? null : Path.of(value.strip());
        }

        /** Whether {@code GIT_CONFIG_NOSYSTEM}, or its property, drops the system file. */
        boolean noSystem() {
            String property = System.getProperty(GitConfigFiles.NOSYSTEM_PROPERTY);
            String value = property != null && !property.isBlank() ? property : getenv("GIT_CONFIG_NOSYSTEM");
            return value != null && !value.isBlank() && !value.strip().matches("(?i)0|false|no|off");
        }
    }
}
