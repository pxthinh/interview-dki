package pxt.dki.integrations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserGoogleAccountRepository extends JpaRepository<UserGoogleAccount, UUID> {

    List<UserGoogleAccount> findByUserIdInAndStatus(Collection<UUID> userIds, GoogleAccountStatus status);

    List<UserGoogleAccount> findByStatus(GoogleAccountStatus status);

    /**
     * Expires the connection only if it still holds the token that failed: a user who
     * reconnected meanwhile (new token) is left alone. Targeted update, so it never overwrites
     * a concurrently stored token either.
     */
    @Modifying
    @Query("""
            update UserGoogleAccount a set a.status = pxt.dki.integrations.GoogleAccountStatus.EXPIRED
            where a.userId = :userId and a.refreshTokenEnc = :tokenEnc""")
    int markExpiredIfToken(@Param("userId") UUID userId, @Param("tokenEnc") String tokenEnc);
}
