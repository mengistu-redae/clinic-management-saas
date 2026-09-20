package com.clinicops.notification;

public interface NotificationSender {
    /** Throw on failure so NotificationWorker can retry - don't swallow errors here. */
    void send(Notification notification) throws Exception;
}
