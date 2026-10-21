// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A served worker for {@link WarmWorkersTest}: the {@code --serve} protocol with no plugin behind
 * it. A spec's first line says what the run does: {@code ok}, {@code retire}, {@code oom} (dies the
 * way {@code ExitOnOutOfMemoryError} does) or {@code hang}.
 */
public final class ServeFixture {

    static final String PREFIX = "##FIX:";

    private ServeFixture() {}

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            emit("{\"t\":\"ready\"}");
            String line = in.readLine();
            if (line == null || line.equals("DONE")) return;
            String what =
                    Files.readAllLines(Path.of(line.substring(4))).getFirst().strip();
            emit("{\"t\":\"pid\",\"pid\":" + ProcessHandle.current().pid() + "}");
            System.out.println("chatter for " + what);
            switch (what) {
                case "oom" -> {
                    System.out.println("java.lang.OutOfMemoryError: Java heap space");
                    System.out.flush();
                    System.exit(3);
                }
                case "hang" -> Thread.sleep(Long.MAX_VALUE);
                default -> {}
            }
            emit("{\"t\":\"exited\",\"code\":" + what.length() + ",\"retire\":" + what.equals("retire") + "}");
        }
    }

    private static void emit(String json) {
        System.out.println(PREFIX + json);
        System.out.flush();
    }
}
