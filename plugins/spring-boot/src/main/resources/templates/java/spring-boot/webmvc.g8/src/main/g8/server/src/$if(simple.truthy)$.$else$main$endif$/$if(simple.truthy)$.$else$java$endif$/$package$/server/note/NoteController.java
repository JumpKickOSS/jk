package $package$.server.note;

import jakarta.validation.Valid;
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

    private final NoteService notes;

    public NoteController(NoteService notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<NoteDto.View> all() {
        return notes.all();
    }

    @GetMapping("/{id}")
    public NoteDto.View one(@PathVariable long id) {
        return notes.one(id);
    }

    @PostMapping
    public ResponseEntity<NoteDto.View> create(@Valid @RequestBody NoteDto.Create in) {
        NoteDto.View saved = notes.create(in);
        return ResponseEntity.created(URI.create("/api/notes/" + saved.id())).body(saved);
    }
}
