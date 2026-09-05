/**
 * Shown when a signed-in USER holds no capability for what was requested.
 *
 * The wording is fixed and says nothing about why. It names no role, no
 * authority and no claim, so the page cannot become a way to read back the
 * session's role set, and it does not tell one user which role would have
 * worked. There is no retry and no sign-out control either: the refusal is
 * about this user's permissions, not about the session being wrong.
 *
 * The primary navigation in `AppShell` stays rendered around this page, so a
 * keyboard user is never stranded here.
 */
export function AccessDeniedPage() {
  return (
    <section aria-labelledby="access-denied-heading">
      <h2 id="access-denied-heading">Access denied</h2>
      <p>You do not have permission to view this page.</p>
    </section>
  );
}
