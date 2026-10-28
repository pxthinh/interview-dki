package pxt.dki.room;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.error.ResourceNotFoundException;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Amenities", description = "Danh mục tiện nghi phòng họp (ADMIN quản lý)")
public class AmenityController {

    private final AmenityRepository amenityRepository;

    public AmenityController(AmenityRepository amenityRepository) {
        this.amenityRepository = amenityRepository;
    }

    public record AmenityRequest(@NotBlank @Size(max = 100) String name) {
    }

    @GetMapping("/amenities")
    @Operation(summary = "Danh mục tiện nghi", description = "Nguồn cho picker form phòng và bộ lọc tìm phòng.")
    public List<String> list() {
        return amenityRepository.findAll().stream()
                .map(Amenity::getName)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @PostMapping("/admin/amenities")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Thêm tiện nghi (ADMIN)",
            description = "Tên không chứa / \\ % ; , hay ký tự điều khiển, và không phải '.' hoặc '..'.")
    public ResponseEntity<Void> create(@Valid @RequestBody AmenityRequest request) {
        String name = request.name().trim();
        AmenityNames.requireValid(name);
        if (amenityRepository.existsById(name)) {
            throw new BusinessRuleException("AMENITY_EXISTS", "Amenity already exists: " + name);
        }
        Amenity amenity = new Amenity();
        amenity.setName(name);
        amenityRepository.save(amenity);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    public record RenameRequest(@NotBlank @Size(max = 100) String name,
                                @NotBlank @Size(max = 100) String newName) {
    }

    @PutMapping("/admin/amenities")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    @Operation(summary = "Đổi tên tiện nghi (ADMIN)",
            description = "Đổi tên trong danh mục và lan sang mọi phòng đang gắn tên cũ.")
    public ResponseEntity<Void> rename(@Valid @RequestBody RenameRequest request) {
        String oldName = request.name().trim();
        String newName = request.newName().trim();
        AmenityNames.requireValid(newName);
        if (!amenityRepository.existsById(oldName)) {
            throw new ResourceNotFoundException("Amenity not found: " + oldName);
        }
        if (newName.equals(oldName)) {
            return ResponseEntity.ok().build();
        }
        if (amenityRepository.existsById(newName)) {
            throw new BusinessRuleException("AMENITY_EXISTS", "Amenity already exists: " + newName);
        }
        amenityRepository.deleteById(oldName); // PK change = delete + insert
        Amenity renamed = new Amenity();
        renamed.setName(newName);
        amenityRepository.save(renamed);
        amenityRepository.renameInRooms(oldName, newName);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/admin/amenities")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Xóa tiện nghi (ADMIN)",
            description = "Tên truyền qua query param `name` (tên tiếng Việt có dấu không đi qua "
                    + "path segment được vì firewall chỉ cho ASCII trên URI). "
                    + "Chỉ gỡ khỏi danh mục; phòng đang gắn tiện nghi này giữ nguyên dữ liệu.")
    public ResponseEntity<Void> delete(@org.springframework.web.bind.annotation.RequestParam String name) {
        if (!amenityRepository.existsById(name)) {
            throw new ResourceNotFoundException("Amenity not found: " + name);
        }
        amenityRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }
}
