/**
 * Thin fetch wrapper - relative paths only, so it works unmodified both in
 * production (SPA served same-origin by node-bff) and in dev (Vite's proxy
 * forwards /api and /auth to node-bff on :3000 - see vite.config.js). The
 * session cookie rides along automatically on same-origin requests, which is
 * the whole point of the BFF pattern: this file never touches a token.
 *
 * Error bodies from spring-boot-api come in two shapes: every controller
 * -local @ExceptionHandler (see e.g. ClinicController.handleClinicNotFound)
 * returns a bare String, but a ResponseStatusException thrown directly with
 * no dedicated handler (overlap/duplicate/bad-status checks, sprinkled
 * across most controllers) falls through to Spring Boot's own default
 * /error JSON body instead - {timestamp,status,error,message,path} once
 * server.error.include-message: always is set (see application.yml; a real
 * bug found live building the clinic-admin UI - without it, that JSON body
 * has no message field at all). parseErrorBody handles both: JSON with a
 * message field is unwrapped to just that string, anything else (plain
 * text, or JSON with no message) is used as-is.
 */
async function parseErrorBody(response) {
  const text = await response.text().catch(() => '');
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    try {
      const body = JSON.parse(text);
      if (body && typeof body.message === 'string' && body.message) {
        return body.message;
      }
    } catch {
      // Not actually JSON despite the header - fall through to the raw text.
    }
  }
  return text;
}
export class ApiError extends Error {
  constructor(status, message) {
    super(message || `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * A 401 means the BFF session itself is gone (expired, never logged in) -
 * not a role/permission problem (those come back as 403 from Spring, and
 * are left for the caller to handle). There's nothing a page can usefully
 * render for "you're not logged in" other than sending the browser to log
 * in, so this is the one cross-cutting concern the client owns centrally
 * rather than every page re-implementing it.
 */
function redirectToLogin() {
  window.location.href = '/auth/login';
}

/**
 * Phase 45: which clinic is "active" for a multi-branch staff member - a
 * plain module-level value rather than React state, since apiFetch is a
 * plain function outside any component tree (same "no premature plumbing"
 * reasoning as redirectToLogin above). ActiveClinicContext is the one
 * writer - it calls setActiveClinicId whenever the user switches branches
 * or on initial load from localStorage. null means "no header" - every
 * single-branch staff login never calls the setter at all, so this stays
 * null and spring-boot-api falls back to the caller's one accessible
 * clinic exactly as before this phase.
 */
let activeClinicId = null;

export function setActiveClinicId(clinicId) {
  activeClinicId = clinicId;
}

export async function apiFetch(path, options = {}) {
  // A FormData body (provider signature upload, phase 13/frontend phase K)
  // must NOT get a manual Content-Type - the browser sets its own
  // multipart boundary parameter, which we can't replicate here.
  const isFormData = options.body instanceof FormData;
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(options.body && !isFormData ? { 'Content-Type': 'application/json' } : {}),
      ...(activeClinicId ? { 'X-Active-Clinic-Id': activeClinicId } : {}),
      ...options.headers,
    },
  });

  if (response.status === 401 && path.startsWith('/api')) {
    redirectToLogin();
    // Never resolves - the navigation above is about to tear this page down.
    return new Promise(() => {});
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorBody(response));
  }

  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) {
    return null;
  }
  return response.json();
}

export function apiGet(path) {
  return apiFetch(path);
}

export function apiPost(path, body) {
  return apiFetch(path, { method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined });
}

/** For a multipart upload (e.g. ProviderController's signature endpoint) - formData is sent as-is, never JSON-stringified. */
export function apiPostForm(path, formData) {
  return apiFetch(path, { method: 'POST', body: formData });
}

export function apiPatch(path, body) {
  return apiFetch(path, { method: 'PATCH', body: JSON.stringify(body) });
}

export function apiDelete(path) {
  return apiFetch(path, { method: 'DELETE' });
}
