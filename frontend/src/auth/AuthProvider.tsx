import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { AuthClient, AuthSession } from "./authClient";
import { AuthContext, type AuthContextValue } from "./authContext";
import { authReducer, initialAuthState } from "./authState";
import { getOidcAuthClient } from "./oidcAuthClient";

export interface AuthProviderProps {
  /** Injected in tests. Production uses the lazily built shared client. */
  readonly client?: AuthClient;
  readonly children: ReactNode;
}

export function AuthProvider({ client, children }: AuthProviderProps) {
  const [authClient] = useState<AuthClient>(() => client ?? getOidcAuthClient());
  const [state, dispatch] = useReducer(authReducer, initialAuthState);
  const signInPendingRef = useRef(false);

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

    authClient.initialize().then(
      (result) => {
        if (!active) {
          return;
        }
        if (result.session === null) {
          dispatch({ type: "init-completed" });
        } else {
          dispatch({ type: "init-restored", session: result.session });
        }
      },
      () => {
        if (active) {
          dispatch({ type: "init-failed" });
        }
      },
    );

    return () => {
      active = false;
      unsubscribe();
    };
  }, [authClient]);

  useEffect(() => {
    /**
     * A sign-in redirect normally ends this document: the browser leaves for
     * the Authorization Server and comes back through `/auth/callback`. But the
     * navigation can be cancelled, or the user can come back to this very
     * document from the back/forward cache, and then nothing ever resolves the
     * pending redirect. The page would stay in `authenticating` with no Sign in
     * button and no way to retry short of a reload.
     *
     * A persisted `pageshow` is the browser telling us this document was
     * restored rather than freshly loaded, which is exactly that case. The
     * pending guard is released and the reducer is asked to leave
     * `authenticating`; its own guard makes this a no-op in every other state,
     * so a normal (non-persisted) `pageshow` cannot cancel a live sign-in. No
     * timeout is involved, and nothing re-authenticates on its own: the user
     * gets the button back and decides.
     */
    const handlePageShow = (event: PageTransitionEvent) => {
      if (!event.persisted || !signInPendingRef.current) {
        return;
      }
      signInPendingRef.current = false;
      dispatch({ type: "sign-in-failed" });
    };

    window.addEventListener("pageshow", handlePageShow);
    return () => {
      window.removeEventListener("pageshow", handlePageShow);
    };
  }, []);

  const signIn = useCallback(
    (returnTo: string) => {
      if (signInPendingRef.current) {
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
    // Local state is dropped first and unconditionally: staying signed in
    // while an async teardown settles is not an acceptable intermediate state.
    dispatch({ type: "signed-out" });
    signInPendingRef.current = false;
    void authClient.signOut().catch(() => {
      // Already unauthenticated locally.
    });
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

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      client: authClient,
      signIn,
      signOut,
      notifyCallbackStarted,
      notifyCallbackSucceeded,
      notifyCallbackFailed,
    }),
    [
      state,
      authClient,
      signIn,
      signOut,
      notifyCallbackStarted,
      notifyCallbackSucceeded,
      notifyCallbackFailed,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
