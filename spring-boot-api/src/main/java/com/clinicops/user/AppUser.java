package com.clinicops.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Local mirror of a Keycloak user, provisioned on first login (see
 * CurrentUserService). Not a BaseTenantEntity - tenantId is nullable and,
 * per the tenancy model, never consulted for authorization once written;
 * it's here purely as a convenience join/mirror, same as app_users in
 * V1__init.sql.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "keycloak_user_id", nullable = false, unique = true)
    private String keycloakUserId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "display_name")
    private String displayName;

    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
