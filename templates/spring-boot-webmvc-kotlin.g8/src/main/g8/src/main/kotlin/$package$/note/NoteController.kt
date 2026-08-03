package $package$.note

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notes")
class NoteController(private val notes: NoteRepository) {

    data class NewNote(@field:NotBlank val text: String)

    @GetMapping
    fun all(): List<Note> = notes.findAll()

    @GetMapping("/{id}")
    fun one(@PathVariable id: Long): ResponseEntity<Note> =
        notes.findById(id).map { ResponseEntity.ok(it) }.orElse(ResponseEntity.notFound().build())

    @PostMapping
    fun create(@Valid @RequestBody incoming: NewNote): ResponseEntity<Note> {
        val saved = notes.save(Note(incoming.text))
        return ResponseEntity.created(URI.create("/api/notes/" + saved.id)).body(saved)
    }
}
