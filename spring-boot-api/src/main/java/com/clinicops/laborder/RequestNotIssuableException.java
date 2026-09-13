package com.clinicops.laborder;

/** Maps to 409 - confirm-and-order (or any other issuing action) attempted on a lab request that isn't (or is no longer) "requested". */
public class RequestNotIssuableException extends RuntimeException {
    public RequestNotIssuableException(String message) {
        super(message);
    }
}
