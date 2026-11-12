package fx.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

class Bad {
    String version(String tool) throws IOException, InterruptedException {
        Process p =
                new ProcessBuilder(tool, "--version").redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }
}
