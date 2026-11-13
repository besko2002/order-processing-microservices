package com.example.orders.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** A registered account. Mapped to the {@code users} table created by {@code V2__create_users.sql}. */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UserAccount() {
        // for JPA
    }

    private UserAccount(String name, String email, String passwordHash, Role role) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * @param name         display name, already trimmed
     * @param email        already normalised via {@link Emails#normalize(String)}
     * @param passwordHash BCrypt hash, never a plain password
     * @param role         assigned role
     * @return a new account with a freshly generated id
     */
    public static UserAccount create(String name, String email, String passwordHash, Role role) {
        return new UserAccount(name, email, passwordHash, role);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
