// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A Windows host made of maps: registry values, environment, volumes, files and git's answer. */
final class FakeHost implements HostFacts, Registry {

    final Map<String, Integer> dwords = new HashMap<>();
    final Map<String, String> strings = new HashMap<>();
    final Map<String, String> env = new HashMap<>();
    final Map<String, Volume> volumes = new HashMap<>();
    final Map<String, String> files = new HashMap<>();
    final Set<String> existing = new HashSet<>();

    @Nullable
    Boolean git = true;

    String arch = "amd64";

    @Nullable
    String jdk = "amd64";

    long delayMs;

    FakeHost() {
        env.put("USERPROFILE", "C:\\Users\\ada");
        env.put("ProgramFiles", "C:\\Program Files");
        dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "IsServiceRunning", 1);
        dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "DisableAntiVirus", 0);
        dword(Hive.LOCAL_MACHINE, WindowsHostCheck.SAC_KEY, "VerifiedAndReputablePolicyState", 0);
        dword(Hive.LOCAL_MACHINE, WindowsHostCheck.FS_KEY, "LongPathsEnabled", 1);
        string(Hive.CURRENT_USER, WindowsHostCheck.PS_KEY, "ExecutionPolicy", "RemoteSigned");
        string(Hive.LOCAL_MACHINE, WindowsHostCheck.ENV_KEY, "PROCESSOR_ARCHITECTURE", "AMD64");
        volumes.put(WinPaths.key("C:\\Users\\ada\\.jk"), new Volume("C:\\", "NTFS"));
        volumes.put(WinPaths.key("C:\\Users\\ada\\src\\app"), new Volume("C:\\", "NTFS"));
    }

    FakeHost dword(Hive hive, String key, String value, int v) {
        dwords.put(hive + "|" + key + "|" + value, v);
        return this;
    }

    FakeHost string(Hive hive, String key, String value, String v) {
        strings.put(hive + "|" + key + "|" + value, v);
        return this;
    }

    @Override
    public @Nullable Integer dword(Hive hive, String key, String value) {
        pause();
        return dwords.get(hive + "|" + key + "|" + value);
    }

    @Override
    public @Nullable String string(Hive hive, String key, String value) {
        pause();
        return strings.get(hive + "|" + key + "|" + value);
    }

    private void pause() {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Registry registry() {
        return this;
    }

    @Override
    public @Nullable String env(String name) {
        return env.get(name);
    }

    @Override
    public @Nullable Volume volume(Path path) {
        return volumes.get(WinPaths.key(path));
    }

    @Override
    public @Nullable Boolean gitLongPaths() {
        return git;
    }

    @Override
    public String processArch() {
        return arch;
    }

    @Override
    public @Nullable String jdkArch() {
        return jdk;
    }

    @Override
    public boolean isFile(Path path) {
        return existing.contains(WinPaths.key(path)) || files.containsKey(WinPaths.key(path));
    }

    @Override
    public @Nullable String read(Path path) {
        return files.get(WinPaths.key(path));
    }
}
