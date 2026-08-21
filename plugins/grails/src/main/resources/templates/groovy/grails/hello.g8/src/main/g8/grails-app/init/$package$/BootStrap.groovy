package $package$

class BootStrap {

    def init = { servletContext ->
        // Hibernate 7 requires an active transaction for writes.
        Note.withTransaction {
            if (Note.count() == 0) {
                new Note(title: 'hello', body: 'from jk').save(flush: true)
            }
        }
    }

    def destroy = {
    }
}
