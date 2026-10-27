package pxt.dki.integrations;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.integrations.dto.GoogleIntegrationStatusResponse;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/calendar.events", "openid", "email");

    private final GoogleOAuthProperties properties;
    private final UserGoogleAccountRepository accounts;
    private final OAuthStateService stateService;
    private final TokenExchanger exchanger;
    private final TokenCipher cipher;
    private final Clock clock;
    private final pxt.dki.calendar.CalendarSyncService calendarSync;
    private final pxt.dki.calendar.BookingUserEventRepository bookingUserEvents;

    public GoogleOAuthService(GoogleOAuthProperties properties, UserGoogleAccountRepository accounts,
                              OAuthStateService stateService, TokenExchanger exchanger,
                              TokenCipher cipher, Clock clock,
                              pxt.dki.calendar.CalendarSyncService calendarSync,
                              pxt.dki.calendar.BookingUserEventRepository bookingUserEvents) {
        this.properties = properties;
        this.accounts = accounts;
        this.stateService = stateService;
        this.exchanger = exchanger;
        this.cipher = cipher;
        this.clock = clock;
        this.calendarSync = calendarSync;
        this.bookingUserEvents = bookingUserEvents;
    }

    /** Outcome of the OAuth round-trip, mapped by the controller to /account?google=... */
    public enum CallbackResult { CONNECTED, ERROR, SCOPE }

    @Transactional(readOnly = true)
    public GoogleIntegrationStatusResponse status(UUID userId) {
        return accounts.findById(userId)
                .map(a -> new GoogleIntegrationStatusResponse(properties.enabled(), true,
                        a.getGoogleEmail(), a.getStatus(), BusinessTime.toApi(a.getConnectedAt())))
                .orElseGet(() -> new GoogleIntegrationStatusResponse(properties.enabled(), false,
                        null, null, null));
    }

    public String buildAuthUrl(UUID userId) {
        requireEnabled();
        return new GoogleAuthorizationCodeRequestUrl(
                properties.clientId(), properties.redirectUri(), SCOPES)
                .setAccessType("offline")
                .setState(stateService.issue(userId))
                .set("prompt", "consent") // force a refresh token even on re-consent
                .build();
    }

    @Transactional
    public CallbackResult handleCallback(String code, String state) {
        if (!properties.enabled() || code == null || code.isBlank() || state == null || state.isBlank()) {
            return CallbackResult.ERROR;
        }
        UUID userId;
        try {
            userId = stateService.parse(state);
        } catch (Exception e) {
            log.warn("OAuth callback with invalid state: {}", e.toString());
            return CallbackResult.ERROR;
        }
        try {
            TokenExchanger.ExchangeResult result = exchanger.exchange(code);
            UserGoogleAccount account = accounts.findById(userId).orElseGet(() -> {
                UserGoogleAccount a = new UserGoogleAccount();
                a.setUserId(userId);
                return a;
            });
            account.setGoogleEmail(result.googleEmail());
            account.setRefreshTokenEnc(cipher.encrypt(result.refreshToken()));
            account.setStatus(GoogleAccountStatus.CONNECTED);
            account.setConnectedAt(clock.instant());
            accounts.save(account);
            // copies made in another Google account are unreachable with this token
            bookingUserEvents.deleteStaleForUser(userId, result.googleEmail());
            // bring meetings booked before (or while disconnected) onto the calendar
            calendarSync.enqueueResync(userId);
            return CallbackResult.CONNECTED;
        } catch (InsufficientScopeException e) {
            log.info("OAuth callback without the Calendar scope for user {}", userId);
            return CallbackResult.SCOPE;
        } catch (Exception e) {
            log.warn("OAuth code exchange failed: {}", e.toString());
            return CallbackResult.ERROR;
        }
    }

    @Transactional
    public void disconnect(UUID userId) {
        accounts.findById(userId).ifPresent(account -> {
            try {
                exchanger.revoke(cipher.decrypt(account.getRefreshTokenEnc()));
            } catch (Exception e) {
                log.warn("Could not revoke Google token for user {}: {}", userId, e.toString());
            }
            accounts.delete(account);
        });
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessRuleException("GOOGLE_OAUTH_DISABLED",
                    "Personal Google Calendar integration is not enabled");
        }
    }
}
