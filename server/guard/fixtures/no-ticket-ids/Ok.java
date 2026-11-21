package fx.ticketids;

class Ok {
    /** The step's wall, without the time it waited for a worker lease. */
    long wall(long total, long held) {
        // TODO(JK-VYJ4X): subtract the time held for memory too
        return total - held;
    }
}
