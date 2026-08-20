package $package$

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.id.LongIdTable

data class Note(val id: Long, val text: String)

data class NewNote(val text: String = "")

object NotesTable : LongIdTable("notes") {
    val text = varchar("text", 512)
}

/** Exposed-backed store; H2 in-memory so the service runs with zero setup. */
class NoteRepository {

    fun init() {
        Database.connect("jdbc:h2:mem:notes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(NotesTable) }
    }

    fun all(): List<Note> = transaction {
        NotesTable.selectAll().map { Note(it[NotesTable.id].value, it[NotesTable.text]) }
    }

    fun add(text: String): Note = transaction {
        val id = NotesTable.insertAndGetId { it[NotesTable.text] = text }
        Note(id.value, text)
    }
}

fun Application.noteRoutes(notes: NoteRepository) {
    routing {
        get("/api/notes") {
            call.respond(notes.all())
        }
        post("/api/notes") {
            val incoming = call.receive<NewNote>()
            if (incoming.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text must not be blank"))
            } else {
                call.respond(HttpStatusCode.Created, notes.add(incoming.text))
            }
        }
    }
}
