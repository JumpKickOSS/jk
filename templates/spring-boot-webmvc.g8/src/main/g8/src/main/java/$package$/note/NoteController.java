package $package$.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    public record NewNote(@NotBlank String text) {}

    private final NoteRepository notes;

    public NoteController(NoteRepository notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<Note> all() {
        return notes.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Note> one(@PathVariable Long id) {
        return notes.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Note> create(@Valid @RequestBody NewNote in) {
        Note saved = notes.save(new Note(in.text()));
        return ResponseEntity.created(URI.create("/api/notes/" + saved.getId())).body(saved);
    }
}
