package com.carparts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A login account.
 *
 * <p>A login is not the same thing as a person, so {@code employee} is optional in both
 * directions: a service account belongs to nobody on the payroll, and staff may have no account
 * at all. It is unique, so one person can never be behind two logins — otherwise revoking
 * somebody's access would mean hunting for every account claiming to be them.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    /**
     * A BCrypt digest at cost 12, never a plaintext password.
     *
     * <p>{@code ck_app_user_password_hashed} refuses anything not shaped like one, which makes
     * writing a plain password here impossible no matter which code does the writing.
     */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.EMPLOYEE;

    /** Disabling keeps an account's history; deleting the row would not. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", unique = true)
    private Employee employee;

    protected AppUser() {
        // for JPA
    }

    // No setters for username, passwordHash or role. Nothing in the application creates or
    // edits an account — the seed migration does, and there is no endpoint for it — so they had
    // no callers, and a settable password digest on an entity is the sort of thing that grows
    // one carelessly. The constructor takes all three; Hibernate reads the fields directly.
    public AppUser(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    // No isManager() here. Reaching it from an account means walking two lazy associations —
    // employee, then department — for a fact v_user_identity already joins in one row. The
    // login path reads that view via ReportingRepository.findIdentityByUsername(). Where you
    // genuinely hold an Employee, Employee.isManager() answers it without a further query.

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AppUser other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return AppUser.class.hashCode();
    }

    /** Deliberately omits the digest, so it can never reach a log. */
    @Override
    public String toString() {
        return "AppUser{id=" + id + ", username='" + username + "', role=" + role + "}";
    }
}
