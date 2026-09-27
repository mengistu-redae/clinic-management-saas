package com.clinicops.accounting;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * The journal entry header - genuinely immutable once posted, same
 * "no update/delete anywhere" precedent as ConsentRecord/PhiAccessLog/
 * DispenseRecord. A mistake is corrected with a reversing entry (a new
 * entry with the debit/credit sides swapped), not an edit - matches this
 * app's own decision, pinned before writing any of this module, that
 * journal entries are immutable rather than editable-until-a-period-close.
 */
@Entity
@Table(name = "journal_entries")
@Getter
@Setter
public class JournalEntry extends BaseTenantEntity {

    @Column(nullable = false)
    private String description;

    /** appointment_payment, lab_order_payment, refund. */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** The Payment's recordedBy / Refund's refundedBy - null only if that field itself was ever null. */
    @Column(name = "posted_by")
    private UUID postedBy;
}
