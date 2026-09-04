import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import type { AuthClient, CompleteSignInResult } from "../auth/authClient";
import { AuthCallbackError, safeAuthErrorMessage } from "../auth/authErrors";
import { classifyCallbackUrl, clearCallbackUrl } from "../auth/callbackUrl";
import { resolveReturnRoute } from "../auth/returnRoute";
import { acquireTransactionStorage, clearAuthTransactionState } from "../auth/transactionStorage";
import { useAuth } from "../auth/useAuth";

interface CallbackRecord {
  readonly promise: Promise<CompleteSignInResult>;
}

/**
 * The authorization response in the address bar is a one-time value, so the
 * work of consuming it is claimed once at module scope rather than once per
 * effect. A StrictMode replay joins the existing record instead of reading a
 * URL that has already been cleared, and the first effect's cleanup cannot
 * cancel work the surviving second effect still needs.
 */
let pendingCallback: CallbackRecord | undefined;

/**
 * Abandons the transaction record for a response this page refuses to hand to
 * the library. Storage is acquired here, never at render or effect setup, so a
 * `window.sessionStorage` getter that throws produces the same fixed callback
 * failure as any other storage fault instead of taking down the React tree.
 */
function discardTransaction(): void {
  try {
    clearAuthTransactionState(acquireTransactionStorage());
  } catch {
    // Reported as the same fixed callback failure either way.
  }
}

function startCallback(client: AuthClient, capturedUrl: string): Promise<CompleteSignInResult> {
  // Step one, before anything can fail on storage: get the authorization
  // response out of the address bar. Fail closed if that is refused — a code
  // that stays visible in history must not also be exchanged for a session.
  try {
    clearCallbackUrl();
  } catch {
    discardTransaction();
    return Promise.reject(new AuthCallbackError());
  }

  // Only now is the captured response classified, and only a clean one reaches
  // the library. A direct entry, an unparseable URL, or a response carrying
  // both `code` and `error` — a shape no Authorization Server may produce — is
  // abandoned here rather than resolved by whichever the library inspects
  // first. This page owns cleanup for those paths because the adapter is never
  // involved in them.
  if (classifyCallbackUrl(capturedUrl) !== "authorization-response") {
    discardTransaction();
    return Promise.reject(new AuthCallbackError());
  }

  // From here the adapter owns transaction cleanup for every library outcome,
  // so cleanup runs exactly once on success and exactly once on failure.
  return client.completeSignIn(capturedUrl);
}

function claimCallback(client: AuthClient): CallbackRecord {
  const existing = pendingCallback;
  if (existing !== undefined) {
    return existing;
  }

  const capturedUrl = window.location.href;
  const record: CallbackRecord = { promise: startCallback(client, capturedUrl) };
  pendingCallback = record;

  // Identity-checked release: a late continuation belonging to an earlier
  // record must not clear a newer one. Both handlers are attached here, so the
  // shared promise never surfaces as an unhandled rejection.
  const release = () => {
    if (pendingCallback === record) {
      pendingCallback = undefined;
    }
  };
  record.promise.then(release, release);

  return record;
}

type CallbackUiState = { readonly status: "pending" } | { readonly status: "failed" };

export function AuthCallbackPage() {
  const { client, notifyCallbackStarted, notifyCallbackSucceeded, notifyCallbackFailed } =
    useAuth();
  const navigate = useNavigate();
  const [ui, setUi] = useState<CallbackUiState>({ status: "pending" });

  useEffect(() => {
    let active = true;
    notifyCallbackStarted();

    const record = claimCallback(client);
    record.promise.then(
      (result) => {
        if (!active) {
          return;
        }
        notifyCallbackSucceeded(result.session);
        // The provider echoed this back through the sign-in transaction, so it
        // is untrusted input until the allowlist has had a look at it.
        navigate(resolveReturnRoute(result.returnTo), { replace: true });
      },
      () => {
        if (!active) {
          return;
        }
        setUi({ status: "failed" });
        notifyCallbackFailed();
      },
    );

    return () => {
      active = false;
    };
  }, [client, navigate, notifyCallbackStarted, notifyCallbackSucceeded, notifyCallbackFailed]);

  return (
    <section aria-labelledby="auth-callback-heading">
      <h2 id="auth-callback-heading">Signing in</h2>
      <div role="status">
        {ui.status === "pending" ? (
          <p>Completing sign-in...</p>
        ) : (
          <p>{safeAuthErrorMessage("callback")}</p>
        )}
      </div>
      {ui.status === "failed" && (
        <p>
          <Link to="/">Return home</Link>
        </p>
      )}
    </section>
  );
}
