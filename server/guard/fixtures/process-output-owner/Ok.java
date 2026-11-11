package fx.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

class Ok {
    byte[] log(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        }
    }

    boolean signal(String pid) throws IOException, InterruptedException {
        Process kill = new ProcessBuilder("kill", "-TERM", pid)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0;
    }
}
