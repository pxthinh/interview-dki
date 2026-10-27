package pxt.dki.integrations;

/** Seam over Google's token endpoint so tests never make HTTP calls. */
public interface TokenExchanger {

    record ExchangeResult(String refreshToken, String googleEmail) {
    }

    /** @throws IllegalStateException when the exchange fails or Google returns no refresh token */
    ExchangeResult exchange(String code);

    /** Best-effort revocation; never throws out. */
    void revoke(String refreshToken);
}
