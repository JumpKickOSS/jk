package $package$.server.note;

import $package$.data.note.Note;
import $package$.data.note.NoteRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {

    private final NoteRepository notes;

    public NoteService(NoteRepository notes) {
        this.notes = notes;
    }

    @Transactional(readOnly = true)
    public List<NoteDto.View> all() {
        return notes.findAll().stream().map(NoteService::toView).toList();
    }

    @Transactional(readOnly = true)
    public NoteDto.View one(long id) {
        return notes.findById(id)
                .map(NoteService::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public NoteDto.View create(NoteDto.Create in) {
        return toView(notes.save(new Note(in.body())));
    }

    private static NoteDto.View toView(Note n) {
        return new NoteDto.View(n.getId(), n.getBody(), n.getCreatedAt());
    }
}
