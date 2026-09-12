package com.clinicops.provider;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "providers")
@Getter
@Setter
public class Provider extends BaseTenantEntity {

    /** The provider's own login, if any - null for a provider with no portal/staff account yet. */
    @Column(name = "app_user_id")
    private UUID appUserId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String specialty;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(nullable = false)
    private String status = "active";
}
