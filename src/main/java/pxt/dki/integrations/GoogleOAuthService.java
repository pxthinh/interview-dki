package pxt.dki.integrations;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pxt.dki.calendar.BookingUserEventRepository;
import pxt.dki.calendar.CalendarSyncService;
import pxt.dki.common.error.BusinessRuleException;
import pxt.dki.common.time.BusinessTime;
import pxt.dki.integrations.dto.GoogleIntegrationStatusResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/calendar.events", "openid", "email");

    /** Outcome of the OAuth round-trip, mapped by the controller to /account?google=... */
    public enum CallbackResult { CONNECTED, ERROR, SCOPE }

    private final GoogleOAuthProperties properties;
    private final UserGoogleAccountRepository accounts;
    private final OAuthStateService stateService;
    private final TokenExchanger exchanger;
    private final TokenCipher cipher;
    private final Clock clock;
    private final CalendarSyncService calendarSync;
    private final BookingUserEventRepository bookingUserEvents;
    private final TransactionTemplate tx;

    public GoogleOAuthService(GoogleOAuthProperties properties, UserGoogleAccountRepository accounts,
                              OAuthStateService stateService, TokenExchanger exchanger,
                              TokenCipher cipher, Clock clock, CalendarSyncService calendarSync,
                              BookingUserEventRepository bookingUserEvents, TransactionTemplate tx) {
        this.properties = properties;
        this.accounts = accounts;
        this.stateService = stateService;
        this.exchanger = exchanger;
        this.cipher = cipher;
        this.clock = clock;
        this.calendarSync = calendarSync;
        this.bookingUserEvents = bookingUserEvents;
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public GoogleIntegrationStatusResponse status(UUID userId) {
        return accounts.findById(userId)
                .map(a -> new GoogleIntegrationStatusResponse(properties.enabled(), true,
                        a.getGoogleEmail(), a.getStatus(), BusinessTime.toApi(a.getConnectedAt())))
                .orElseGet(() -> new GoogleIntegrationStatusResponse(properties.enabled(), false,
                        null, null, null));
    }

    /** Browser-binding nonce for one connect attempt; the controller puts it in a cookie. */
    public String newNonce() {
        requireEnabled();
        return stateService.newNonce();
    }

    public String buildAuthUrl(UUID userId, String nonce) {
        requireEnabled();
        return new GoogleAuthorizationCodeRequestUrl(
                properties.clientId(), properties.redirectUri(), SCOPES)
                .setAccessType("offline")
                .setState(stateService.issue(userId, nonce))
                .set("prompt", "consent") // force a refresh token even on re-consent
                .build();
    }

    /**
     * Never throws: every failure maps to ERROR so the browser always lands on the account page.
     * Google is called with no transaction open; only the final writes run in one.
     */
    public CallbackResult handleCallback(String code, String state, String cookieNonce) {
        if (!properties.enabled() || isBlank(code) || isBlank(state) || isBlank(cookieNonce)) {
            return CallbackResult.ERROR;
        }
        UUID userId;
        try {
            OAuthStateService.State parsed = stateService.parse(state);
            if (parsed.nonce() == null || !MessageDigest.isEqual(
                    parsed.nonce().getBytes(StandardCharsets.UTF_8), cookieNonce.getBytes(StandardCharsets.UTF_8))) {
                log.warn("OAuth callback from a browser that did not start this connect");
                return CallbackResult.ERROR;
            }
            userId = parsed.userId();
        } catch (Exception e) {
            log.warn("OAuth callback with invalid state: {}", e.toString());
            return CallbackResult.ERROR;
        }

        TokenExchanger.ExchangeResult result;
        try {
            result = exchanger.exchange(code);
        } catch (InsufficientScopeException e) {
            log.info("OAuth callback without the Calendar scope for user {}", userId);
            return CallbackResult.SCOPE;
        } catch (Exception e) {
            log.warn("OAuth code exchange failed: {}", e.toString());
            return CallbackResult.ERROR;
        }

        try {
            String tokenEnc = cipher.encrypt(result.refreshToken());
            tx.executeWithoutResult(s -> {
                UserGoogleAccount account = accounts.findById(userId).orElseGet(() -> {
                    UserGoogleAccount a = new UserGoogleAccount();
                    a.setUserId(userId);
                    return a;
                });
                account.setGoogleEmail(result.googleEmail());
                account.setRefreshTokenEnc(tokenEnc);
                account.setStatus(GoogleAccountStatus.CONNECTED);
                account.setConnectedAt(clock.instant());
                accounts.saveAndFlush(account);
                // copies made in another Google account are unreachable with this token
                bookingUserEvents.deleteStaleForUser(userId, result.googleEmail());
                // bring meetings booked before (or while disconnected) onto the calendar
                calendarSync.enqueueResync(userId);
            });
            return CallbackResult.CONNECTED;
        } catch (Exception e) {
            log.warn("Could not store the Google connection of user {}: {}", userId, e.toString());
            exchanger.revoke(result.refreshToken()); // granted but not kept: do not leave it dangling
            return CallbackResult.ERROR;
        }
    }

    /** Removes the link; the Google token is revoked only once the removal has committed. */
    @Transactional
    public void disconnect(UUID userId) {
        accounts.findById(userId).ifPresent(account -> {
            String token = null;
            try {
                token = cipher.decrypt(account.getRefreshTokenEnc());
            } catch (Exception e) {
                log.warn("Could not read the Google token of user {} for revocation: {}", userId, e.toString());
            }
            accounts.delete(account);
            if (token != null) {
                revokeAfterCommit(token);
            }
        });
    }

    private void revokeAfterCommit(String token) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    exchanger.revoke(token);
                }
            });
        } else {
            exchanger.revoke(token);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessRuleException("GOOGLE_OAUTH_DISABLED",
                    "Personal Google Calendar integration is not enabled");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
