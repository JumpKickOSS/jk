package $package$.server.note

import jakarta.validation.Valid
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
class NoteController(private val notes: NoteService) {
    @GetMapping
    fun all(): List<NoteView> = notes.all()

    @GetMapping("/{id}")
    fun one(@PathVariable id: Long): NoteView = notes.one(id)

    @PostMapping
    fun create(@Valid @RequestBody input: NewNote): ResponseEntity<NoteView> {
        val saved = notes.create(input)
        return ResponseEntity.created(URI.create("/api/notes/" + saved.id)).body(saved)
    }
}
