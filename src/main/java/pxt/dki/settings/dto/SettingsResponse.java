package pxt.dki.settings.dto;

public record SettingsResponse(int maxDaysAhead, int maxRecurringWeeks,
                               boolean calendarSyncEnabled, boolean mailEnabled) {
}
