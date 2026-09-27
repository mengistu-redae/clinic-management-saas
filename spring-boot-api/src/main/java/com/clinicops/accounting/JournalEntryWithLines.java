package com.clinicops.accounting;

import java.util.List;

/** Same "wrapper record for one always-together response" shape as EncounterWithPrescriptions/LabOrderWithTests - no JPA relation between the two, so the controller composes them itself. */
public record JournalEntryWithLines(JournalEntry entry, List<JournalLine> lines) {
}
