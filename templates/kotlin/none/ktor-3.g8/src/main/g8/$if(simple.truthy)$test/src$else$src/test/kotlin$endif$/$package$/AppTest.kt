package $package$

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppTest {

    @Test
    fun createsAndListsNotes() = testApplication {
        application { module() }
        val created = client.post("/api/notes") {
            contentType(ContentType.Application.Json)
            setBody("{\"text\":\"first\"}")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val list = client.get("/api/notes")
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("first"))
    }

    @Test
    fun rejectsBlankText() = testApplication {
        application { module() }
        val bad = client.post("/api/notes") {
            contentType(ContentType.Application.Json)
            setBody("{\"text\":\"\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
