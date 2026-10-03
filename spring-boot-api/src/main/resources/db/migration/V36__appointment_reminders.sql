-- Patient engagement, SMS reminders (sketched 2026-10-04) - the first of
-- three sequential patient-engagement phases (SMS reminders, then secure
-- messaging, then satisfaction surveys), per the user's own answer.
--
-- No real SMS gateway (Twilio etc.) exists in this dev environment, the
-- identical blocker phase 17 hit for email before Mailpit was introduced -
-- per the user's own answer, this phase deliberately skips real delivery
-- entirely: a reminder is written to the existing `notifications` outbox
-- with channel='sms' and a status that NotificationWorker's own
-- `findTop50ByStatusOrderByCreatedAtAsc("pending")` query will never pick
-- up (see AppointmentReminderScheduler), so no change to NotificationWorker/
-- SmtpEmailSender was needed - this is intent-tracking only until a real
-- gateway exists.

ALTER TABLE appointments ADD COLUMN reminder_sent_at TIMESTAMPTZ;
