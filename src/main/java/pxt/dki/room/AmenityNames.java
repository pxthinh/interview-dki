package pxt.dki.room;

import pxt.dki.common.error.BusinessRuleException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Amenity name rules shared by the catalog endpoints and the room create/update path. */
final class AmenityNames {

    static final int MAX_LENGTH = 100;
    /** Upper bound on amenities per room; keeps the text[] column and the UI pickers sane. */
    static final int MAX_PER_ROOM = 20;

    private AmenityNames() {
    }

    /**
     * '/', '\', '%', ';' break URLs; ',' breaks the list filter (a single ?amenities=a,b value is
     * split on commas); control characters (newlines...) break pickers and slip past regexes.
     */
    static void requireValid(String name) {
        boolean invalid = name == null || name.isBlank() || name.length() > MAX_LENGTH
                || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> "/\\%;,".indexOf(c) >= 0 || Character.isISOControl(c));
        if (invalid) {
            throw new BusinessRuleException("AMENITY_INVALID_NAME",
                    "Amenity name must not contain / \\ % ; , or control characters, or be '.'/'..'");
        }
    }

    /** Trims every item, validates it, drops duplicates (first occurrence wins) and caps the list. */
    static List<String> normalize(List<String> amenities) {
        if (amenities == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : amenities) {
            String name = raw == null ? null : raw.trim();
            requireValid(name);
            unique.add(name);
        }
        if (unique.size() > MAX_PER_ROOM) {
            throw new BusinessRuleException("AMENITY_LIST_TOO_LONG",
                    "A room may have at most " + MAX_PER_ROOM + " amenities");
        }
        return new ArrayList<>(unique);
    }
}
