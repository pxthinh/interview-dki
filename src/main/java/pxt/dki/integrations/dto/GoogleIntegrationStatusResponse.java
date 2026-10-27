package pxt.dki.integrations.dto;

import pxt.dki.integrations.GoogleAccountStatus;

import java.time.OffsetDateTime;

public record GoogleIntegrationStatusResponse(
        boolean enabled,
        boolean connected,
        String googleEmail,
        GoogleAccountStatus status,
        OffsetDateTime connectedAt) {
}
