package pxt.dki.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, UUID> {
}
