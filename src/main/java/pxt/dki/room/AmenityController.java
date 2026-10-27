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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    @Operation(summary = "Thêm tiện nghi (ADMIN)")
    public ResponseEntity<Void> create(@Valid @RequestBody AmenityRequest request) {
        String name = request.name().trim();
        if (amenityRepository.existsById(name)) {
            throw new BusinessRuleException("AMENITY_EXISTS", "Amenity already exists: " + name);
        }
        Amenity amenity = new Amenity();
        amenity.setName(name);
        amenityRepository.save(amenity);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/admin/amenities/{name}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Xóa tiện nghi (ADMIN)",
            description = "Chỉ gỡ khỏi danh mục; phòng đang gắn tiện nghi này giữ nguyên dữ liệu.")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!amenityRepository.existsById(name)) {
            throw new ResourceNotFoundException("Amenity not found: " + name);
        }
        amenityRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }
}
