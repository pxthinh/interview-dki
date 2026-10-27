package pxt.dki.settings.dto;

/** Null field = leave unchanged. */
public record UpdateSettingsRequest(Integer maxDaysAhead, Integer maxRecurringWeeks,
                                    Boolean calendarSyncEnabled, Boolean mailEnabled) {
}
