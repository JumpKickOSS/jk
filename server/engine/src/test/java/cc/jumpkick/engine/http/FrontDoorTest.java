// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Guest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front door's parts that need no running engine: discovery over the band ports, the pairing
 * file a WSL guest writes into the Windows home and how it finds that home, and what a path under
 * {@code /engines/<id>/} may be. The proxy itself, behind the token gate, is {@link FrontDoorProxyTest}.
 */
class FrontDoorTest {

    private static final String GUEST_ID = "caf4beecf11d";

    @TempDir
    Path guests;

    private final List<HttpServer> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(s -> s.stop(0));
    }

    /** An engine answering only {@code /api/identity}, as id {@code id}, on an ephemeral loopback port. */
    private int engineAnswering(String id) throws IOException {
        return engineAnswering(id, "guest");
    }

    /** As {@link #engineAnswering(String)}, saying it has role {@code role}. */
    private int engineAnswering(String id, String role) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = s.getAddress().getPort();
        s.createContext(
                "/api/identity",
                ex -> send(
                        ex,
                        "{\"id\":\"" + id + "\",\"label\":\"WSL Ubuntu on box\",\"role\":\"" + role + "\",\"port\":"
                                + port + "}"));
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        started.add(s);
        return port;
    }

    private final List<Socket> held = new ArrayList<>();

    @AfterEach
    void releasePorts() throws IOException {
        for (Socket s : held) s.close();
    }

    /**
     * A loopback port with nothing listening, held bound (not listening) for the test, so a later
     * ephemeral bind cannot be handed the same port: under load the OS reused a released one for an
     * engine, and the band then named that engine's port twice.
     */
    private int closedPort() throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        held.add(s);
        return s.getLocalPort();
    }

    /** A second listener answering as engine {@code id} that says it serves on {@code port}. */
    private int alsoAnswering(String id, int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext(
                "/api/identity",
                ex -> send(ex, "{\"id\":\"" + id + "\",\"label\":\"WSL Ubuntu on box\",\"port\":" + port + "}"));
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        started.add(s);
        return s.getAddress().getPort();
    }

    private static void send(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private FrontDoor door(List<Integer> ports) {
        EngineIdentity self = new EngineIdentity("90cec5c68a24", "windows", Guest.NONE, "box", "C:/me/.jk", "1.0");
        return new FrontDoor(() -> self, () -> 8910, guests, ports);
    }

    @Test
    void discovery_lists_itself_then_each_engine_answering_on_the_band_and_says_which_are_paired() throws Exception {
        int guest = engineAnswering(GUEST_ID);
        int closed = closedPort();
        List<Integer> band = List.of(closed, guest);

        List<Map<String, Object>> unpaired = door(band).engines();
        assertThat(unpaired).extracting(m -> m.get("id")).containsExactly("90cec5c68a24", GUEST_ID);
        assertThat(unpaired.get(0)).containsEntry("self", true).containsEntry("paired", true);
        assertThat(unpaired.get(1))
                .containsEntry("self", false)
                .containsEntry("paired", false)
                .containsEntry("label", "WSL Ubuntu on box");

        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, guest, "guest-token"));
        assertThat(door(band).engines().get(1)).containsEntry("paired", true);

        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, closed, "guest-token"));
        assertThat(door(band).engines().get(1)).as("a pairing for another port").containsEntry("paired", false);

        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, guest, "guest-token"));
        assertThat(door(List.of(closed)).engines())
                .as("an engine off the band is not looked for, paired or not")
                .extracting(m -> m.get("id"))
                .containsExactly("90cec5c68a24");
    }

    @Test
    void discovery_lists_each_engine_once_at_the_port_it_serves_on() throws Exception {
        int guest = engineAnswering(GUEST_ID);
        int second = alsoAnswering(GUEST_ID, guest);
        for (List<Integer> band : List.of(List.of(guest, guest), List.of(second, guest), List.of(guest, second))) {
            List<Map<String, Object>> engines = door(band).engines();
            assertThat(engines)
                    .as("band " + band)
                    .extracting(m -> m.get("id"))
                    .containsExactly("90cec5c68a24", GUEST_ID);
            assertThat(engines.get(1).get("port"))
                    .as("an integer port, at the one it serves on")
                    .isEqualTo(guest);
        }
    }

    @Test
    void discovery_skips_an_answer_that_is_not_an_engine_and_an_engine_that_is_this_one() throws Exception {
        int self = engineAnswering("90cec5c68a24");
        int notHex = engineAnswering("../../etc");
        assertThat(door(List.of(self, notHex)).engines())
                .extracting(m -> m.get("id"))
                .containsExactly("90cec5c68a24");
    }

    @Test
    void discovery_leaves_out_a_host_engine_on_the_band_which_is_another_homes_on_this_host() throws Exception {
        int otherHome = engineAnswering("0be7a1e5c0de", "host");
        int guest = engineAnswering(GUEST_ID);
        assertThat(door(List.of(otherHome, guest)).engines())
                .as("a scratch home's engine moved off 8910 is no guest to switch to")
                .extracting(m -> m.get("id"))
                .containsExactly("90cec5c68a24", GUEST_ID);
    }

    @Test
    void discovery_asks_every_band_port_at_once() throws Exception {
        List<Integer> band = new ArrayList<>();
        for (int i = 0; i < 40; i++) band.add(closedPort());
        band.add(engineAnswering(GUEST_ID));
        long t0 = System.nanoTime();
        List<Map<String, Object>> engines = door(band).engines();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(engines).hasSize(2);
        // Serially, a Windows host's ~1 s per refused loopback port made this 40 s.
        assertThat(ms).isLessThan(5_000);
    }

    @Test
    void a_pairing_round_trips_owner_only_and_goes_away_when_removed() throws Exception {
        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, 8988, "guest-token"));
        assertThat(GuestPairing.read(guests))
                .containsEntry(GUEST_ID, new GuestPairing.Pair(GUEST_ID, 8988, "guest-token"));
        Path file = GuestPairing.file(guests, GUEST_ID);
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
        }

        GuestPairing.remove(guests, GUEST_ID);
        assertThat(GuestPairing.read(guests)).isEmpty();
        assertThat(GuestPairing.read(guests.resolve("missing"))).isEmpty();
    }

    @Test
    void a_pairing_file_is_read_only_when_named_for_the_engine_it_names() throws Exception {
        Files.writeString(guests.resolve("0123456789ab.toml"), "id = \"caf4beecf11d\"\nport = 8912\ntoken = \"t\"\n");
        Files.writeString(guests.resolve("zz.toml"), "id = \"zz\"\nport = 8912\ntoken = \"t\"\n");
        Files.writeString(guests.resolve("aaaaaaaaaaaa.toml"), "id = \"aaaaaaaaaaaa\"\nport = 8912\ntoken = \"\"\n");
        Files.writeString(guests.resolve("bbbbbbbbbbbb.toml"), "not toml at all");
        assertThat(GuestPairing.read(guests)).isEmpty();
        assertThatThrownBy(() -> GuestPairing.write(guests, new GuestPairing.Pair("../x", 8912, "t")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_wsl_guest_finds_the_windows_engines_guests_directory_through_interop() {
        Map<String, String> windows = new HashMap<>();
        windows.put("USERPROFILE", "C:\\Users\\me");
        GuestPairing.Interop interop = argv -> {
            if (argv.getFirst().equals("wslpath")) {
                return "/mnt/c/" + argv.get(2).substring(3).replace('\\', '/') + "\n";
            }
            String name = argv.get(3).replace("echo ", "").replace("%", "");
            return windows.getOrDefault(name, "%" + name + "%") + "\r\n";
        };
        assertThat(GuestPairing.windowsDir(interop)).isEqualTo(Path.of("/mnt/c/Users/me/.jk/state/engine/guests"));

        windows.put("JK_HOME", "D:\\jk");
        assertThat(GuestPairing.windowsDir(
                        argv -> argv.getFirst().equals("wslpath") ? "/mnt/d/jk/state\n" : interop.output(argv)))
                .isEqualTo(Path.of("/mnt/d/jk/state/engine/guests"));

        windows.put("JK_STATE_DIR", "E:\\state");
        assertThat(GuestPairing.windowsDir(
                        argv -> argv.getFirst().equals("wslpath") ? "/mnt/e/state\n" : interop.output(argv)))
                .isEqualTo(Path.of("/mnt/e/state/engine/guests"));

        assertThat(GuestPairing.windowsDir(argv -> null)).as("no interop").isNull();
    }

    @Test
    void a_path_under_the_prefix_names_the_engine_and_the_path_the_engine_will_route() {
        assertThat(FrontDoor.target("/engines/caf4beecf11d/api/status"))
                .isEqualTo(new FrontDoor.Target(GUEST_ID, "/api/status", "/api/status"));
        assertThat(FrontDoor.target("/engines/caf4beecf11d/")).isEqualTo(new FrontDoor.Target(GUEST_ID, "/", "/"));
        assertThat(FrontDoor.target("/engines/caf4beecf11d")).isEqualTo(new FrontDoor.Target(GUEST_ID, "", ""));
        assertThat(FrontDoor.target("/engines/caf4beecf11d/a%20b.css"))
                .isEqualTo(new FrontDoor.Target(GUEST_ID, "/a%20b.css", "/a b.css"));

        assertThat(FrontDoor.target("/api/status")).isNull();
        assertThat(FrontDoor.target("/engines/../api/status")).isNull();
        assertThat(FrontDoor.target("/engines/CAF4BEECF11D/api/status")).isNull();
        // Each of these could reach the engine as a path other than the one the front door gated.
        assertThat(FrontDoor.target("/engines/caf4beecf11d/api%3F/x"))
                .as("an escaped ?")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/x%23/api/build"))
                .as("an escaped #")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/x%2F..%2Fapi/build"))
                .as("an escaped /")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/x%5Capi"))
                .as("an escaped \\")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/x%2561pi"))
                .as("a double escape")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/x/../api/build"))
                .as("a dot segment")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/./api/build")).isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d//api/build"))
                .as("an empty segment")
                .isNull();
        assertThat(FrontDoor.target("/engines/caf4beecf11d/%00"))
                .as("a control character")
                .isNull();

        assertThat(FrontDoor.Kind.of("GET", "/api/events")).isEqualTo(FrontDoor.Kind.EVENTS);
        assertThat(FrontDoor.Kind.of("GET", "/api/status")).isEqualTo(FrontDoor.Kind.API);
        assertThat(FrontDoor.Kind.of("GET", "/api")).isEqualTo(FrontDoor.Kind.API);
        assertThat(FrontDoor.Kind.of("GET", "/mcp")).isEqualTo(FrontDoor.Kind.MCP);
        assertThat(FrontDoor.Kind.of("GET", "/report/t/r1/index.html")).isEqualTo(FrontDoor.Kind.REPORT);
        assertThat(FrontDoor.Kind.of("GET", "/apix")).isEqualTo(FrontDoor.Kind.OPEN);
        assertThat(FrontDoor.Kind.of("GET", "/index.html")).isEqualTo(FrontDoor.Kind.OPEN);
        assertThat(FrontDoor.Kind.of("GET", "/api/identity"))
                .as("read without a token, as on the engine itself")
                .isEqualTo(FrontDoor.Kind.OPEN);
        assertThat(FrontDoor.Kind.of("HEAD", "/api/identity")).isEqualTo(FrontDoor.Kind.API);
        assertThat(FrontDoor.Kind.of("POST", "/api/identity")).isEqualTo(FrontDoor.Kind.API);
    }

    @Test
    void an_engines_location_is_put_back_under_its_prefix_with_the_browsers_token() {
        URI sent = URI.create("http://127.0.0.1:8911/report/g/r1/");
        String id = "caf4beecf11d";
        assertThat(FrontDoor.location("/report/g/r1/a/index.html?x=1#top", sent, id, "f", "g"))
                .isEqualTo("/engines/caf4beecf11d/report/f/r1/a/index.html?x=1#top");
        assertThat(FrontDoor.location("http://127.0.0.1:8911/report/g/r1/a.html", sent, id, "f", "g"))
                .isEqualTo("/engines/caf4beecf11d/report/f/r1/a.html");
        assertThat(FrontDoor.location("./a/b%20c.html", sent, id, "f", "g"))
                .isEqualTo("/engines/caf4beecf11d/report/f/r1/a/b%20c.html");
        assertThat(FrontDoor.location("/#settings", sent, id, null, "g")).isEqualTo("/engines/caf4beecf11d/#settings");
        assertThat(FrontDoor.location("/report/g/r1/", sent, id, null, "g"))
                .as("no browser token to put in its place")
                .isNull();
        assertThat(FrontDoor.location("https://127.0.0.1:8911/x", sent, id, "f", "g"))
                .isNull();
        assertThat(FrontDoor.location("http://localhost:8911/x", sent, id, "f", "g"))
                .isNull();
        assertThat(FrontDoor.location("http://u@127.0.0.1:8911/x", sent, id, "f", "g"))
                .isNull();
        assertThat(FrontDoor.location("/../../x", sent, id, "f", "g")).isNull();
        assertThat(FrontDoor.location("/a%2Fb", sent, id, "f", "g")).isNull();
        assertThat(FrontDoor.location("not a uri", sent, id, "f", "g")).isNull();
        assertThat(FrontDoor.carries("/x?t=%67%2d%74", "g-t")).isTrue();
        assertThat(FrontDoor.carries("/x?t=a+b", "a b")).isFalse();
    }

    @Test
    void the_browsers_access_token_never_leaves_and_the_engines_takes_its_place_only_when_asked() {
        assertThat(FrontDoor.forwardedQuery("access_token=front&lastEventId=7", "g t"))
                .isEqualTo("access_token=g+t&lastEventId=7");
        assertThat(FrontDoor.forwardedQuery("lastEventId=7&access_token=front", null))
                .isEqualTo("lastEventId=7");
        assertThat(FrontDoor.forwardedQuery("access_token=front", null)).isNull();
        assertThat(FrontDoor.forwardedQuery("lastEventId=7", "g"))
                .as("no token asked for")
                .isEqualTo("lastEventId=7");
        assertThat(FrontDoor.forwardedQuery(null, "g")).isNull();

        assertThat(FrontDoor.reportPath("/report/front/r1/index.html", "g")).isEqualTo("/report/g/r1/index.html");
        assertThat(FrontDoor.reportPath("/report/front/r1/index.html", null)).isNull();
        assertThat(FrontDoor.reportToken("/report/front/r1/")).isEqualTo("front");
        assertThat(FrontDoor.reportToken("/report/front")).isNull();
    }

    @Test
    void a_closed_port_answers_no_identity() throws Exception {
        assertThat(door(List.of()).identityAt(closedPort())).isNull();
    }

    @Test
    void the_front_door_tells_each_paired_engine_with_its_own_token_and_asks_the_8910_holder_to_step_aside()
            throws Exception {
        List<String> heard = new CopyOnWriteArrayList<>();
        HttpServer band = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        band.createContext(FrontDoor.CONTROL, ex -> {
            heard.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, "{\"stepped\":true}");
        });
        band.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        band.start();
        started.add(band);
        int bandPort = band.getAddress().getPort();
        // The guest's 8910 listener: answers its identity as a guest.
        HttpServer front = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        front.createContext(
                "/api/identity", ex -> send(ex, "{\"id\":\"" + GUEST_ID + "\",\"role\":\"guest\",\"port\":8910}"));
        front.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        front.start();
        started.add(front);
        int frontPort = front.getAddress().getPort();

        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, bandPort, "guest-token"));
        GuestPairing.write(guests, new GuestPairing.Pair("90cec5c68a24", bandPort, "self-token"));
        FrontDoor door = door(List.of(bandPort));

        assertThat(door.tell("present"))
                .as("this engine's own pairing is skipped")
                .isEqualTo(1);
        assertThat(door.askToStepAside(frontPort)).isTrue();
        assertThat(heard)
                .containsExactly(
                        "POST /api/front-door/present Bearer guest-token",
                        "POST /api/front-door/step-aside Bearer guest-token");

        heard.clear();
        int notJk = engineAnswering("0123456789ab");
        assertThat(door.askToStepAside(notJk))
                .as("an unpaired holder is not asked")
                .isFalse();
        assertThat(door.askToStepAside(closedPort())).isFalse();
        assertThat(heard).isEmpty();
    }

    @Test
    void the_front_door_token_is_the_hosts_token_and_the_front_door_file_wins() throws Exception {
        Path own = guests.resolve("abc.http-token");
        Path front = FrontDoorToken.file(guests);
        FrontDoorToken.align(guests, own);
        assertThat(front).doesNotExist();

        Files.writeString(own, "host-token");
        FrontDoorToken.align(guests, own);
        assertThat(Files.readString(front).strip()).as("seeded from the host's").isEqualTo("host-token");

        Files.writeString(front, "shared-token");
        FrontDoorToken.align(guests, own);
        assertThat(Files.readString(own).strip()).as("adopted by the host").isEqualTo("shared-token");
    }
}
