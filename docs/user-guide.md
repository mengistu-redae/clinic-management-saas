# User Guide

This guide covers everyday use of the clinic management system for every
role: Patient, Front Desk, Provider, Clinic Admin, Pharmacist, Accountant,
and Platform Admin. It describes what you can do and where to find it — not
how the system is built (see the other documents in `docs/` for that).

## Logging in and out

Staff and patients both sign in through the same login page. When you open
the app and you're not already signed in, you're sent to a secure login
screen (hosted by the clinic's identity provider, Keycloak) — enter your
username and password there. Once you're signed in, the app recognizes your
role automatically and shows you the dashboard and menu for that role; you
never pick a role yourself.

To sign out, use the **account menu** in the top-right corner of the page
(click your username/account icon) and choose **Log Out**. Always use this
button rather than just closing the browser tab — it's what actually ends
your secure session. Closing the tab without logging out can leave you (or
the next person on a shared computer) still signed in.

If you don't have an account yet, a few flows are reachable without signing
in at all — see **Guest booking and tracking** under Patient, below.

---

## Patient

As a patient, your dashboard shows your upcoming appointments and any open
lab orders at a glance, with quick links into the full lists and into
booking a new appointment.

### Booking an appointment

1. Go to **Book Appointment**.
2. Pick a clinic.
3. Pick an appointment type and, if you have a preference, a specific
   provider.
4. Pick an available time slot from the ones shown.
5. Confirm. You'll land on a confirmation page showing your appointment
   reference — if you booked without an account (see below), **save this
   reference and the phone number you booked with**, since that's the only
   way to look the appointment up again later.

### Managing your appointments

**My Appointments** lists everything you've booked, with a status for each
(Booked, Checked In, Roomed, With Provider, Checked Out, No Show,
Cancelled) and a search box. Open any appointment to:

- **Reschedule it** — pick a new time for the same provider/type.
- **Cancel it** — you'll be asked to confirm. Depending on the clinic's
  policy and how much notice you give, a cancellation or late-reschedule
  fee may apply.
- **Download a visit summary** — once a visit has happened, you can
  download a PDF covering the clinical note, vitals, prescriptions,
  allergies, and any immunizations given that visit.

### Lab orders and prescriptions

**My Lab Orders** lists every lab test you've requested or that's been
ordered for you, with its current status (Requested → Ordered → Specimen
Collected → In Transit → Resulted → Reviewed). You can also request a new
test yourself from here — describe what you'd like tested and which
clinic, and staff will confirm it, assign a provider, and price it at your
next visit.

**My Prescriptions** lists prescriptions from your visits, including how
much of each has already been dispensed. If you need a refill, open the
prescription and click **Request Refill** — a pharmacist will review it and
you'll see whether it was approved or denied (with a reason, if denied).
You can only have one pending refill request per prescription at a time.

### Guest booking and tracking

You don't need an account to book an appointment or request a lab test —
the booking and request forms work for guests too, using just your contact
details. Without an account, though, you can't come back to a "My
Appointments" list, so two public, no-login pages exist instead:

- **Track an Appointment** — enter your appointment reference and the phone
  number you booked with, to look up its status anytime.
- **Track a Lab Order** — same idea, for a lab order's reference and phone
  number.

---

## Front Desk

Your dashboard shows today's appointment activity at a glance. Your main
day-to-day work happens across three areas:

### Patients

**Book a Walk-In** is where you search for an existing patient (by name,
phone, or national ID) or register a new one, then book an appointment for
them directly. A patient's record also shows their **Patient Chart** —
allergies, vitals, immunizations, and consent records. You can record
vitals and immunizations yourself (this is the one clinical area front
desk has access to, since most clinics don't have a separate nursing
role); allergies and consent are visible to you but recording a new
allergy or consent entry is also within your access. Clinical notes
(encounters) themselves are provider-only.

### Appointments

**Appointments** lists every appointment at the clinic, searchable and
filterable by status. Opening one lets you walk it through the check-in
sequence as the patient physically arrives and moves through their visit:

**Checked In → Roomed → With Provider → Checked Out**

Each step is its own button — click the next one as it happens. You can
also:

- **Record a payment** against the appointment.
- **Reschedule or cancel** it (the same fee rules apply as for a patient
  self-cancelling).
- **Cancel an entire recurring series** — if the appointment is part of a
  recurring booking, you'll see an option to cancel it and every remaining
  occurrence in the series at once, not just the one you're looking at.

### Inventory

Front desk shares the general **Inventory** module with Clinic Admin —
clinical supplies, PPE, office supplies, and equipment, as distinct from
the pharmacy's own medication stock. Four pages:

- **Items** — the supply catalog, with stock batches you can receive and
  (when a batch is expired/damaged) write off, plus low-stock alerts.
- **Suppliers** — your vendor list.
- **Purchase Orders** — place and receive orders against a supplier,
  mixing medication and general-item lines on the same order.
- **Assets** — equipment tracking: status (in service / under maintenance
  / retired / disposed), which room it's assigned to, and a maintenance
  log you can add entries to.

---

## Provider

Your dashboard is **Today's Schedule** — every appointment booked with you
today, with a toggle to filter it down to patients you haven't seen yet
versus those you have.

### Documenting a visit (Encounter)

Open an appointment once the patient is with you to document the visit:

- **Chief complaint, assessment, and plan** — free text.
- **ICD-10 codes** — free text as well (not validated against a coded
  lookup).
- **Physical exam** — a 9-system checklist (general appearance, HEENT,
  cardiovascular, respiratory, abdominal, musculoskeletal, neurological,
  skin, psychiatric). For each system, mark Normal/Abnormal/Not Examined
  and add a note; an abnormal finding is visually flagged so it's easy to
  spot at a glance.
- **Prescriptions** — a full list of what you're prescribing this visit
  (route, frequency, duration, quantity, refills). Saving replaces the
  whole list, so include everything still active, not just what's new.

Also on this page: the patient's allergies, immunizations, vitals, and
consent records (read-only reference while you document), plus any
addenda already added to a previously-signed note.

### Signing a note

When you're done, click **Sign Encounter**. This locks the note and its
prescriptions — every field becomes read-only. If you need to correct or
add something after signing, you can't edit the original note directly;
instead add an **Addendum**, which is its own dated, permanent entry
appended to the record.

### Referrals and lab orders

**Referrals** lets you refer a patient either internally (to another
provider at the same clinic) or externally (outside the clinic), with a
reason and clinical summary. **Lab Orders** lets you review and act on
tests ordered for your patients, searchable and filterable by status.

---

## Clinic Admin

Clinic Admin is the broadest role in the system — you manage how the
clinic itself is configured, see clinic-wide analytics, and also have
override access into the pharmacy, finance, and inventory modules (so you
can step in on any of those without needing a second login), reachable via
their own labeled sections in your sidebar.

### Your own dashboard and config

Your dashboard shows appointment volume, revenue, status breakdown, and
provider utilization charts over a selectable time window (7/30/90 days).

From your sidebar you manage:

- **Providers** — add providers, set their working hours, license info,
  employment status, link them to a login, and upload a signature image.
- **Rooms** and **Appointment Types** — the clinic's physical rooms and
  the kinds of visits it offers, each with an active/inactive status.
- **Fee Policies** — tiered no-show/late-cancellation fees, either
  clinic-wide or overridden per provider.
- **Lab Rates** — pricing for each lab test code the clinic orders.
- **Settings** — tax rate, default fees, notice periods, and the clinic's
  timezone.
- **Branding** — the clinic's display name, logo, and color scheme.

### Access to other modules

Your sidebar also has sections for **Pharmacy**, **Finance**, and
**Inventory** — these are each covered under their own role below
(Pharmacist, Accountant, Front Desk), and you have the same access to
them that those roles do.

---

## Pharmacist

Your dashboard is the **dispense queue** — every active prescription still
waiting to be fully dispensed, with charts showing recent dispensing
volume and top medications.

### Dispensing

Open a queue entry to dispense it: pick the catalog medication it
corresponds to (the prescription itself is just the provider's free-text
note — you match it to a real catalog item and a specific stock batch),
enter the quantity, and confirm. If the system detects a possible issue —
an allergy conflict or a known interaction with another medication the
patient is on — you'll see a warning and have to explicitly acknowledge it
before the dispense goes through. Each dispense has its own history you
can review, including billing (recording a payment or generating an
invoice for it).

### Medication catalog

**Medications** is the full catalog — add medications, receive stock
batches, write off expired or damaged stock, and filter the list down to
active-only, low-stock, or controlled substances to triage what needs
attention. Controlled substances are flagged with a schedule badge (a
clinic admin sets which schedule applies).

### Controlled substances

Dispensing a controlled substance doesn't go through the normal one-step
flow — it needs **two people**. You (or another pharmacist) submit a
request; a *different* pharmacist or a clinic admin must then co-sign it
before the stock is actually decremented and the dispense is finalized.
You can also reject a request you no longer want to go through with. This
is managed under **Controlled Substances** in your sidebar.

### Drug interactions and refill requests

**Drug Interactions** is where the pairs of medications the system checks
for during dispensing are maintained — add, edit, or delete a pair and its
severity. **Refill Requests** is your review queue for patient-submitted
refill requests: approve (the patient is notified by email) or deny with a
reason.

---

## Accountant

Your dashboard summarizes cash balance, revenue, expenses, and net income
for the current period, with quick links into each of the pages below.

- **Accounts** — the chart of accounts (asset/liability/equity/revenue/
  expense), filterable by type. A few starter accounts (Cash, Service
  Revenue, Refunds & Allowances, Salary Expense) exist automatically.
- **Journal** — every transaction the system has posted automatically
  (payments, refunds, payroll runs), plus the trial balance. This is
  read-only and grows continuously, so it has its own period (year/month)
  and transaction-type filters to help you find what you're looking for.
- **Employees** — staff opted into payroll, linked to their existing login
  by email, with a salary and active/inactive status.
- **Payroll** — run payroll for a given month (one balanced entry is
  posted per active employee); a month can only be run once. History of
  past runs is listed below, with each run expandable to see the
  per-employee payments it produced.
- **Budgets** — set a monthly budget per account, then review
  budget-vs-actual variance and a profit-and-loss summary for any period,
  all driven by one year/month selector at the top of the page.

---

## Platform Admin

Platform Admin manages the clinics on the platform itself — you're not
scoped to any one clinic.

### Onboarding a new clinic

From **Clinics**, fill in the new clinic's name, an org alias, and its
domain. You can optionally also provide an email and name for its first
Clinic Admin user — if you do, that login is created automatically and a
**one-time temporary password** is shown once in a banner on screen. Copy
it immediately (a copy button is provided) and hand it to the new clinic's
admin out of band — it cannot be retrieved again once you dismiss the
banner. If you leave the admin fields blank, the clinic is onboarded with
nobody able to log in yet, to be set up later.

### Deactivating and reactivating a clinic

Each clinic in the list can be deactivated or reactivated. **Deactivating
a clinic locks out every staff login at that clinic** — it's a tenant-wide
action, not a minor toggle, which is why it's styled as a clearly
destructive action rather than a plain link. Reactivating restores access
immediately.

---

## Preferences

A few viewer preferences are available to every signed-in role, in the
top bar:

- **Language** — English or Amharic (full interface translation).
- **Theme** — Light, Dark, or System (follows your device's own setting).
- **Timezone display** — how times are shown: your browser's local
  timezone, the clinic's own timezone, or a timezone you pick manually.

These are saved per device/browser, not to your account — they'll reset
if you sign in from a different computer or browser.
