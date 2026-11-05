// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** An in-memory keychain: tests decide whether it is there, locked, or refusing. */
public final class FakeKeychain implements SecretBackend {

    public final Map<String, String> items = new ConcurrentHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public @Nullable String unavailable;
    public @Nullable String locked;
    public @Nullable String refusing;
    public int probes;

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public String displayName() {
        return "Fake Keychain";
    }

    @Override
    public synchronized Probe probe() {
        probes++;
        if (unavailable != null) return Probe.unavailable(unavailable);
        return locked == null ? Probe.AVAILABLE : Probe.refused(locked);
    }

    @Override
    public synchronized Optional<String> read(String item) {
        calls.add("read " + item);
        refuseIfAsked();
        return Optional.ofNullable(items.get(item));
    }

    @Override
    public synchronized void write(String item, String label, String secret) {
        calls.add("write " + item);
        refuseIfAsked();
        items.put(item, secret);
    }

    @Override
    public synchronized void delete(String item) {
        calls.add("delete " + item);
        refuseIfAsked();
        items.remove(item);
    }

    private void refuseIfAsked() {
        if (refusing != null) throw new SecretBackendException(refusing);
    }
}
