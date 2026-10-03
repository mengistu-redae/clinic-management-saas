package com.clinicops.insurance;

/** claimNumber is optional at submission time - a payer doesn't always hand one back immediately; it can be filled in later via a second submit call before adjudication. */
public record SubmitClaimRequest(
        String claimNumber
) {
}
