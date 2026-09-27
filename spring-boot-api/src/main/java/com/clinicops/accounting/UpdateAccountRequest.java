package com.clinicops.accounting;

/** Partial update - only non-null fields applied. {@code code} isn't here at all - fixed at creation, see Account's own javadoc. */
public record UpdateAccountRequest(
        String name,
        String status
) {
}
