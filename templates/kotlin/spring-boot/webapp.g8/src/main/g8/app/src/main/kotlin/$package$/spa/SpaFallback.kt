package $package$.spa

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Client-side routing: a GET for a page the server does not know — no `/api` prefix, no file extension, HTML acceptable
 * — gets `index.html` from the web module's jar, never cached, so a deep link and a reload land in the SPA's router.
 * Everything else that misses is a 404 problem detail. Hashed assets under `/assets` keep their own route.
 */
@ControllerAdvice
class SpaFallback {

    /** Empty when the bundle was not built: the API still serves, the pages 404. */
    private val index: ByteArray =
        SpaFallback::class.java.getResourceAsStream(INDEX)?.use { it.readAllBytes() } ?: ByteArray(0)

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<*> {
        if (index.isNotEmpty() && isPage(request)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(index)
        }
        val problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND)
        problem.instance = URI.create(request.requestURI)
        return ResponseEntity.of(problem).build<Any>()
    }

    companion object {
        const val INDEX = "/static/index.html"

        /** A page: GET or HEAD, outside the API, no extension in the last segment, and the client takes HTML. */
        fun isPage(request: HttpServletRequest): Boolean {
            if (request.method != "GET" && request.method != "HEAD") return false
            val path = request.requestURI
            if (path == "/api" || path.startsWith("/api/")) return false
            if (path.substringAfterLast('/').contains('.')) return false
            val accept = request.getHeader("Accept")
            return accept == null || accept.contains("text/html") || accept.contains("*/*")
        }
    }
}
