import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { AuthClient, AuthSession } from "./authClient";
import { AuthContext, type AuthContextValue } from "./authContext";
import { authReducer, initialAuthState } from "./authState";
import { classifyLogoutCallbackUrl, clearLogoutCallbackUrl } from "./logoutCallbackUrl";
import { getOidcAuthClient } from "./oidcAuthClient";

/** Completes a root end-session response from the captured URL string. */
export type CompleteSignOut = (callbackUrl: string) => Promise<void>;

interface LogoutCallbackRecord {
  readonly promise: Promise<void>;
}

/**
 * The end-session response in the address bar is a one-time value, so the work
 * of consuming it is claimed once at module scope rather than once per effect.
 * A StrictMode replay joins the existing record instead of reading a URL that
 * has already been cleared, and the first setup's cleanup cannot cancel work
 * the surviving second setup still needs.
 */
let pendingLogoutCallback: LogoutCallbackRecord | undefined;

/**
 * Classifies the captured address bar and, when it is a root end-session
 * response, starts consuming it.
 *
 * The URL is cleared to a bare `/` before the library is called, so no state
 * and no provider error description survives in the address bar or in
 * `document.referrer` even when the response is then refused. A refusal that
 * happens before the library is involved consumes nothing: it removes no
 * transaction, no user and no session, because a malformed or stale response
 * says nothing about the session this page load may already hold.
 */
function claimLogoutCallback(completeSignOut: CompleteSignOut): LogoutCallbackRecord | undefined {
  const existing = pendingLogoutCallback;
  if (existing !== undefined) {
    return existing;
  }

  const capturedUrl = window.location.href;
  const kind = classifyLogoutCallbackUrl(capturedUrl, window.location.origin);
  if (kind === "none") {
    return undefined;
  }

  const promise = (() => {
    try {
      clearLogoutCallbackUrl();
    } catch {
      return Promise.reject(new Error("The end-session response could not be cleared."));
    }
    if (kind !== "logout-response") {
      // An error response, or a shape no Authorization Server may produce.
      // Neither reaches the library, and neither tears anything down.
      return Promise.reject(new Error("The end-session response was refused."));
    }
    return completeSignOut(capturedUrl);
  })();

  const record: LogoutCallbackRecord = { promise };
  pendingLogoutCallback = record;

  // Identity-checked release: a late continuation belonging to an earlier
  // record must not clear a newer one. Both handlers are attached here, so the
  // shared promise never surfaces as an unhandled rejection.
  const release = () => {
    if (pendingLogoutCallback === record) {
      pendingLogoutCallback = undefined;
    }
  };
  record.promise.then(release, release);

  return record;
}

export interface AuthProviderProps {
  /** Injected in tests. Production uses the lazily built shared client. */
  readonly client?: AuthClient;
  /**
   * Injected in tests. Production completes the root end-session response
   * through the same shared adapter.
   *
   * It is a separate prop rather than a method on `AuthClient` on purpose:
   * `AuthClient` is what this provider publishes to the React tree, and
   * consuming a one-time logout transaction is a page-load concern the provider
   * owns. Keeping it here means no component can reach it at all.
   */
  readonly completeSignOut?: CompleteSignOut;
  readonly children: ReactNode;
}

export function AuthProvider({ client, completeSignOut, children }: AuthProviderProps) {
  const [authClient] = useState<AuthClient>(() => client ?? getOidcAuthClient());
  const [completeSignOutCallback] = useState<CompleteSignOut>(
    () =>
      completeSignOut ?? ((callbackUrl: string) => getOidcAuthClient().completeSignOut(callbackUrl)),
  );
  const [state, dispatch] = useReducer(authReducer, initialAuthState);
  const signInPendingRef = useRef(false);
  const signOutPendingRef = useRef(false);

  useEffect(() => {
    // Per-setup flag rather than a shared ref: under StrictMode the second
    // setup must subscribe and receive events normally, while results owned by
    // the discarded first setup are dropped. Blocking the whole second effect
    // would leave the surviving tree with no listener at all.
    let active = true;

    const unsubscribe = authClient.onSessionInvalidated(() => {
      if (active) {
        dispatch({ type: "session-invalidated" });
      }
    });

    /**
     * The root end-session response is classified and consumed before the first
     * initialization, and initialization waits for it.
     *
     * The order is the point. An initialization sweeps abandoned transaction
     * records off the root route, and the logout transaction the response is
     * about to be validated against lives in exactly that prefix. Running the
     * two concurrently would let the sweep destroy the one-time value the
     * callback needs, so they are sequenced here rather than raced.
     *
     * The callback outcome only ever produces an unauthenticated page or the
     * fixed sign-out error: it publishes no session and holds no credential.
     */
    const startUp = async (): Promise<void> => {
      const logoutCallback = claimLogoutCallback(completeSignOutCallback);
      if (logoutCallback !== undefined) {
        dispatch({ type: "logout-callback-started" });
        try {
          await logoutCallback.promise;
          if (!active) {
            return;
          }
          dispatch({ type: "logout-callback-succeeded" });
        } catch {
          if (!active) {
            return;
          }
          dispatch({ type: "logout-callback-failed" });
        }
      }

      let result: Awaited<ReturnType<AuthClient["initialize"]>>;
      try {
        result = await authClient.initialize();
      } catch {
        if (active) {
          dispatch({ type: "init-failed" });
        }
        return;
      }
      if (!active) {
        return;
      }
      if (result.session === null) {
        dispatch({ type: "init-completed" });
      } else {
        dispatch({ type: "init-restored", session: result.session });
      }
    };

    void startUp();

    return () => {
      active = false;
      unsubscribe();
    };
  }, [authClient, completeSignOutCallback]);

  useEffect(() => {
    /**
     * A redirect normally ends this document: the browser leaves for the
     * Authorization Server and comes back through `/auth/callback` on the way
     * in, or through the application root on the way out. But either navigation
     * can be cancelled, or the user can come back to this very document from the
     * back/forward cache, and then nothing ever resolves the pending redirect.
     * The page would stay in `authenticating` or `signing-out` with no button at
     * all and no way to retry short of a reload.
     *
     * A persisted `pageshow` is the browser telling us this document was
     * restored rather than freshly loaded, which is exactly that case. The
     * pending guard is released and the reducer is asked to leave the pending
     * state; its own guards make this a no-op everywhere else, so a normal
     * (non-persisted) `pageshow` cannot cancel a live redirect. No timeout is
     * involved, and nothing re-authenticates on its own: the user gets a button
     * back and decides.
     */
    const handlePageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) {
        return;
      }
      if (signInPendingRef.current) {
        signInPendingRef.current = false;
        dispatch({ type: "sign-in-failed" });
        return;
      }
      /**
       * The same situation on the way out, with one difference that matters:
       * the local logout already happened and is never undone. So the pending
       * guard is released and the machine leaves `signing-out` as
       * unauthenticated rather than as an error — the user gets the Sign in
       * button back and decides. Nothing retries the redirect and nothing
       * signs anyone back in.
       */
      if (signOutPendingRef.current) {
        signOutPendingRef.current = false;
        dispatch({ type: "sign-out-cancelled" });
      }
    };

    window.addEventListener("pageshow", handlePageShow);
    return () => {
      window.removeEventListener("pageshow", handlePageShow);
    };
  }, []);

  const signIn = useCallback(
    (returnTo: string) => {
      // Neither direction may start while the other is in flight: a sign-in
      // begun during a logout redirect would race a session that is already
      // gone, and the reducer's own guards would silently drop half of it.
      if (signInPendingRef.current || signOutPendingRef.current) {
        return;
      }
      signInPendingRef.current = true;
      dispatch({ type: "sign-in-started" });
      authClient.signIn(returnTo).then(
        () => {
          // The browser is navigating to the Authorization Server; nothing
          // further to do here, and the guard stays closed until then.
        },
        () => {
          signInPendingRef.current = false;
          dispatch({ type: "sign-in-failed" });
        },
      );
    },
    [authClient],
  );

  const signOut = useCallback(() => {
    if (signOutPendingRef.current) {
      return;
    }
    signOutPendingRef.current = true;
    // The authenticated session leaves the UI first and unconditionally.
    // Staying signed in while a redirect is prepared is not an acceptable
    // intermediate state, and `signing-out` carries no session and no
    // capability, so nothing in the tree can keep acting on one.
    dispatch({ type: "sign-out-started" });
    signInPendingRef.current = false;
    authClient.signOut().then(
      () => {
        // The browser is navigating to the end-session endpoint; nothing
        // further to do here, and the guard stays closed until then.
      },
      () => {
        // The remote half failed. The local logout is not restored: the user
        // stays signed out of this browser and sees the fixed message.
        signOutPendingRef.current = false;
        dispatch({ type: "sign-out-failed" });
      },
    );
  }, [authClient]);

  const notifyCallbackStarted = useCallback(() => {
    dispatch({ type: "callback-started" });
  }, []);

  const notifyCallbackSucceeded = useCallback((session: AuthSession) => {
    dispatch({ type: "callback-succeeded", session });
  }, []);

  const notifyCallbackFailed = useCallback(() => {
    dispatch({ type: "callback-failed" });
  }, []);

  /**
   * The value published to the React tree is built here, method by method,
   * rather than being the adapter itself.
   *
   * The adapter also carries the credential capability that can sign a request,
   * and a value handed to the tree is readable by everything in it. Spreading
   * or forwarding the adapter would put that capability one property lookup
   * away from any component, any third-party render prop and any devtools
   * inspection. This object literal has only the public methods, and its
   * prototype is plain `Object.prototype`, so there is nothing else to reach.
   *
   * Memoized on the adapter, which `useState` keeps stable for the lifetime of
   * the provider, so the identity does not change between renders and the
   * callback route's effect does not re-run.
   */
  const publicClient = useMemo<AuthClient>(
    () => ({
      initialize: () => authClient.initialize(),
      signIn: (returnTo: string) => authClient.signIn(returnTo),
      completeSignIn: (callbackUrl: string) => authClient.completeSignIn(callbackUrl),
      signOut: () => authClient.signOut(),
      onSessionInvalidated: (listener: () => void) => authClient.onSessionInvalidated(listener),
    }),
    [authClient],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      client: publicClient,
      signIn,
      signOut,
      notifyCallbackStarted,
      notifyCallbackSucceeded,
      notifyCallbackFailed,
    }),
    [
      state,
      publicClient,
      signIn,
      signOut,
      notifyCallbackStarted,
      notifyCallbackSucceeded,
      notifyCallbackFailed,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
