package com.clinicops.finance;

import java.util.List;

/** Same "wrapper record for one always-together response" shape as EncounterWithPrescriptions/JournalEntryWithLines - no JPA relation between the two, so the controller composes them itself. */
public record PayrollRunWithPayments(PayrollRun run, List<PayrollPayment> payments) {
}
