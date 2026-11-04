// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** An in-memory keychain: tests decide whether it answers, refuses, or is daemon-reachable. */
public final class FakeKeychain implements SecretBackend {

    public final Map<String, String> items = new ConcurrentHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public @Nullable String unavailable;
    public @Nullable String refusing;
    public boolean daemon;

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public String displayName() {
        return "Fake Keychain";
    }

    @Override
    public Probe probe() {
        return unavailable == null ? Probe.AVAILABLE : Probe.unavailable(unavailable);
    }

    @Override
    public boolean daemonReachable() {
        return daemon;
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
