package pxt.dki.integrations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserGoogleAccountRepository extends JpaRepository<UserGoogleAccount, UUID> {

    List<UserGoogleAccount> findByUserIdInAndStatus(Collection<UUID> userIds, GoogleAccountStatus status);
}
