package com.carparts.repository;

import com.carparts.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Case-sensitive, matching {@code uq_app_user_username}. If logins should ever treat
     * {@code Admin} and {@code admin} as the same account, that belongs in the database as a
     * unique index on {@code lower(username)} rather than here — otherwise two accounts could
     * still be created and only the lookup would be confused.
     */
    Optional<AppUser> findByUsername(String username);

    // No existsByUsername or findByEmployeeId. Neither has had a caller since they were
    // written. Logging in needs the account itself, not whether one exists, and a pre-check for
    // a taken username would be a race as well as a duplicate of uq_app_user_username. There is
    // no user-management endpoint for the employee lookup to serve; when one arrives it can ask
    // for exactly what it needs.
}
