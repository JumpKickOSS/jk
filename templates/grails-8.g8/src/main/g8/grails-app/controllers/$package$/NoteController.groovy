package $package$

import grails.gorm.transactions.ReadOnly

@ReadOnly
class NoteController {

    static responseFormats = ['json']

    def index() {
        respond Note.list()
    }

    def show(Long id) {
        respond Note.get(id)
    }
}
