package pxt.dki.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pxt.dki.AbstractIntegrationTest;
import pxt.dki.TestData;
import pxt.dki.auth.AuthUser;
import pxt.dki.booking.dto.CreateBookingRequest;
import pxt.dki.common.error.BookingConflictException;
import pxt.dki.room.RoomRepository;
import pxt.dki.user.UserRepository;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BookingConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    BookingService bookingService;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    RoomRepository roomRepository;

    @Autowired
    UserRepository userRepository;

    @Test
    void tenConcurrentCreates_exactlyOneSucceeds() throws Exception {
        var room = roomRepository.findByNameIgnoreCase("Jupiter").orElseThrow();
        var user = userRepository.findByEmailIgnoreCase("alice@dki.local").orElseThrow();
        AuthUser actor = new AuthUser(user.getId(), user.getEmail(), user.getRole());

        var day = TestData.nextWeekday();
        CreateBookingRequest request = new CreateBookingRequest(
                room.getId(), "All hands", null, null,
                TestData.at(day, 9, 0), TestData.at(day, 10, 0), null);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    bookingService.create(actor, request);
                    created.incrementAndGet();
                } catch (BookingConflictException e) {
                    conflicted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(threads - 1);
        UUID roomId = room.getId();
        long activeCount = bookingRepository.findActiveOverlapping(roomId,
                TestData.at(day, 9, 0).toInstant(), TestData.at(day, 10, 0).toInstant(), null).size();
        assertThat(activeCount).isEqualTo(1);
    }
}
