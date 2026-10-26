package pxt.dki.room;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.room.dto.RoomRequest;
import pxt.dki.room.dto.RoomResponse;
import pxt.dki.room.dto.RoomStatusRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public List<RoomResponse> list(@RequestParam(required = false) Boolean active) {
        return roomService.list(active);
    }

    @GetMapping("/{id}")
    public RoomResponse get(@PathVariable UUID id) {
        return roomService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(@Valid @RequestBody RoomRequest request) {
        return roomService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse update(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
        return roomService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse setStatus(@PathVariable UUID id, @Valid @RequestBody RoomStatusRequest request) {
        return roomService.setActive(id, request.active());
    }
}
