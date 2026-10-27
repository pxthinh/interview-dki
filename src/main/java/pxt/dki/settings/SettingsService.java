package pxt.dki.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.settings.dto.SettingsResponse;
import pxt.dki.settings.dto.UpdateSettingsRequest;

/**
 * Admin-tunable booking limits, stored in app_settings. Reads fall back to the seeded defaults
 * when a row is missing or unparsable, so a broken value can never brick booking creation.
 */
@Service
public class SettingsService {

    public static final String MAX_DAYS_AHEAD = "booking.max-days-ahead";
    public static final String MAX_RECURRING_WEEKS = "booking.max-recurring-weeks";
    static final int DEFAULT_MAX_DAYS_AHEAD = 90;
    static final int DEFAULT_MAX_RECURRING_WEEKS = 26;

    private final SettingsRepository repository;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    public int maxDaysAhead() {
        return read(MAX_DAYS_AHEAD, DEFAULT_MAX_DAYS_AHEAD);
    }

    public int maxRecurringWeeks() {
        return read(MAX_RECURRING_WEEKS, DEFAULT_MAX_RECURRING_WEEKS);
    }

    public SettingsResponse current() {
        return new SettingsResponse(maxDaysAhead(), maxRecurringWeeks());
    }

    @Transactional
    public SettingsResponse update(UpdateSettingsRequest request) {
        if (request.maxDaysAhead() != null) {
            requireRange("maxDaysAhead", request.maxDaysAhead(), 1, 365);
            write(MAX_DAYS_AHEAD, request.maxDaysAhead());
        }
        if (request.maxRecurringWeeks() != null) {
            requireRange("maxRecurringWeeks", request.maxRecurringWeeks(), 2, 52);
            write(MAX_RECURRING_WEEKS, request.maxRecurringWeeks());
        }
        return current();
    }

    private void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new BusinessRuleException("SETTING_OUT_OF_RANGE",
                    name + " must be between " + min + " and " + max);
        }
    }

    private void write(String key, int value) {
        AppSetting row = repository.findById(key).orElseGet(() -> {
            AppSetting s = new AppSetting();
            s.setKey(key);
            return s;
        });
        row.setValue(String.valueOf(value));
        repository.save(row);
    }

    private int read(String key, int fallback) {
        try {
            return repository.findById(key)
                    .map(s -> Integer.parseInt(s.getValue()))
                    .orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
