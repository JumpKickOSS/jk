package $package$.spa;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Client-side routing: a GET for a page the server does not know — no {@code /api} prefix, no file
 * extension, HTML acceptable — gets {@code index.html} from the web module's jar, never cached, so a
 * deep link and a reload land in the SPA's router. Everything else that misses is a 404 problem
 * detail. Hashed assets under {@code /assets} keep their own route.
 */
@ControllerAdvice
public class SpaFallback {

    static final String INDEX = "/static/index.html";

    /** Empty when the bundle was not built: the API still serves, the pages 404. */
    private final byte[] index = load();

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(HttpServletRequest request) {
        if (index.length > 0 && isPage(request)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noStore())
                    .body(index);
        }
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.of(problem).build();
    }

    /** A page: GET or HEAD, outside the API, no extension in the last segment, and the client takes HTML. */
    static boolean isPage(HttpServletRequest request) {
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) return false;
        String path = request.getRequestURI();
        if (path.equals("/api") || path.startsWith("/api/")) return false;
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.contains(".")) return false;
        String accept = request.getHeader("Accept");
        return accept == null || accept.contains("text/html") || accept.contains("*/*");
    }

    private static byte[] load() {
        try (InputStream in = SpaFallback.class.getResourceAsStream(INDEX)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + INDEX, e);
        }
    }
}
