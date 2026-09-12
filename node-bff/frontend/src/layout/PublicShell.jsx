/**
 * Logged-out landing - just enough to drive the auth loop end to end until
 * the patient portal (phase 2) has real pages to show here.
 */
export default function PublicShell() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 px-4 text-center">
      <h1 className="text-2xl font-semibold text-ink">Clinic Management</h1>
      <p className="max-w-sm text-sm text-ink-muted">
        Sign in as clinic staff or a patient to continue.
      </p>
      <a
        href="/auth/login"
        className="rounded-md bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-dark"
      >
        Log in
      </a>
    </div>
  );
}
