package $package$.data.note

import java.time.LocalDateTime
import org.jooq.DSLContext
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Repository

@Repository
open class NoteRepository {
    @Autowired
    lateinit var dsl: DSLContext

    private val notes = table("notes")
    private val id = field("id", SQLDataType.BIGINT.identity(true))
    private val body = field("body", SQLDataType.VARCHAR(2000))
    private val createdAt = field("created_at", SQLDataType.LOCALDATETIME)

    open fun findAll(): List<Note> =
        dsl.select(id, body, createdAt)
            .from(notes)
            .orderBy(id)
            .fetch { r -> Note(r[id], r[body], r[createdAt]) }

    open fun findById(noteId: Long): Note? =
        dsl.select(id, body, createdAt)
            .from(notes)
            .where(id.eq(noteId))
            .fetchOne { r -> Note(r[id], r[body], r[createdAt]) }

    open fun insert(text: String): Note {
        val rec =
            dsl.insertInto(notes)
                .set(body, text)
                .set(createdAt, LocalDateTime.now())
                .returningResult(id, body, createdAt)
                .fetchOne()
                ?: error("insert returned no row")
        return Note(rec[id], rec[body], rec[createdAt])
    }
}
