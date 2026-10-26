package pxt.dki.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.auth.AuthUser;
import pxt.dki.booking.dto.BookingResponse;
import pxt.dki.booking.dto.CreateBookingRequest;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal AuthUser principal,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = bookingService.create(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/bookings/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable UUID id) {
        return bookingService.get(principal, id);
    }
}
