// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.util.TestHomes;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Whether this engine takes part in the machine's front door at all. Only the person's own engine
 * does: an engine in a test sandbox (its home under {@code test-homes}), in a private home ({@code
 * JK_HOME} set) or under CI performs no front-door duty and starts no Windows process. It does not
 * serve the engine list or the proxy, take or hand over 8910, write the shared front-door token,
 * send heartbeats, pair with the Windows engine, start it, check from Windows that 8910 reaches it,
 * or toast on Windows. Such engines are many and short-lived (every suite that runs a real engine in
 * a sandbox starts several), and each would otherwise spawn {@code cmd.exe}, {@code wslpath} and
 * {@code powershell.exe} through WSL interop, write a pairing into the person's Windows profile, and
 * contend for 8910 with the person's engines. {@value #ENV}{@code =true} opts such an engine in, as
 * a test of the front door itself does; {@code =false} opts any engine out.
 */
public final class FrontDoorScope {

    /** The environment variable that opts an engine in ({@code true}) or out ({@code false}). */
    public static final String ENV = "JK_FRONT_DOOR";

    private FrontDoorScope() {}

    /** Whether this process's engine takes part in the front door. */
    public static boolean active() {
        return active(ProcessEnv::get, JkDirs.home());
    }

    /** Whether an engine with environment {@code env} and JumpKick home {@code home} takes part. */
    public static boolean active(Function<String, @Nullable String> env, Path home) {
        Optional<Boolean> asked = EnvValues.bool(env, ENV);
        if (asked.isPresent()) return asked.get();
        if (TestHomes.contains(home)) return false;
        String jkHome = env.apply("JK_HOME");
        if (jkHome != null && !jkHome.isBlank()) return false;
        return !EnvValues.isCi(env);
    }
}
