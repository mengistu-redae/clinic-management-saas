package com.clinicops.accounting;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One side of a {@link JournalEntry} - own table, no JPA relation, same "plain UUID FK + explicit repository queries" convention as LabOrderTest. */
@Entity
@Table(name = "journal_lines")
@Getter
@Setter
public class JournalLine extends BaseTenantEntity {

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    /** debit, credit. */
    @Column(name = "entry_type", nullable = false)
    private String entryType;
}
