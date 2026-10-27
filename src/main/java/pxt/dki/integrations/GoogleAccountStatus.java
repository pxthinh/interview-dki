package pxt.dki.integrations;

/** CONNECTED = token usable; EXPIRED = refresh failed (revoked), the user must reconnect. */
public enum GoogleAccountStatus {
    CONNECTED, EXPIRED
}
