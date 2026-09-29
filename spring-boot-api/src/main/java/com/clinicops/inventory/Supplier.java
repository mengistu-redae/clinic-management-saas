package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Soft-deactivate only, same shape as Room/Provider - referenced by FK from PurchaseOrder. */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
public class Supplier extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_name")
    private String contactName;

    private String phone;

    private String email;

    /** active, inactive. */
    @Column(nullable = false)
    private String status = "active";
}
