package com.clinicops.clinic;

/**
 * The tenant a staff JWT resolved to (via TenantContextFilter) no longer has
 * a corresponding clinics row - shouldn't happen in practice (a clinic is
 * deactivated via its status column, never deleted), but a repository
 * .orElseThrow() should never surface a bare NoSuchElementException. Maps to
 * 404 in ClinicController's own @ExceptionHandler - one exception class per
 * file, no shared @ControllerAdvice, per this codebase's convention.
 */
public class ClinicNotFoundException extends RuntimeException {

    public ClinicNotFoundException(String message) {
        super(message);
    }
}
