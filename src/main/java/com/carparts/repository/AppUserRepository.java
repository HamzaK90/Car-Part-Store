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

    boolean existsByUsername(String username);

    Optional<AppUser> findByEmployeeId(Long employeeId);
}
