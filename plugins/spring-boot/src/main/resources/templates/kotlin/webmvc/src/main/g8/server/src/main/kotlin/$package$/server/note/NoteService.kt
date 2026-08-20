package $package$.server.note

import $package$.data.note.Note
import $package$.data.note.NoteRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class NoteService(private val notes: NoteRepository) {
    fun all(): List<NoteView> = notes.findAll().map(::toView)

    fun one(id: Long): NoteView =
        notes.findById(id)?.let(::toView) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    fun create(input: NewNote): NoteView = toView(notes.insert(input.body))

    private fun toView(n: Note) = NoteView(n.id, n.body, n.createdAt)
}
