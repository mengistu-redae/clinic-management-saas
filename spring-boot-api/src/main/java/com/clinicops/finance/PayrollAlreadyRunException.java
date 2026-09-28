package com.clinicops.finance;

/** Maps to HTTP 409 in PayrollController - payroll for a given (tenant, year, month) can be run at most once, same "immutable once issued" convention as Invoice. */
public class PayrollAlreadyRunException extends RuntimeException {
    public PayrollAlreadyRunException(String message) {
        super(message);
    }
}
