import type {
  AuthorizedRequest,
  AuthSession,
  CompleteSignInResult,
  CredentialAuthClient,
  InitializeResult,
} from "../auth/authClient";

export interface Deferred<T> {
  readonly promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

export function createDeferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  // A deferred that a test never settles must not surface as an unhandled
  // rejection when it is later rejected, so a no-op handler is attached here.
  promise.catch(() => undefined);
  return { promise, resolve, reject };
}

export interface FakeAuthClientCalls {
  /** Raw port invocations: one per React effect setup. */
  initialize: number;
  /** Actual initialization runs, after the shared in-flight promise. */
  initializeWork: number;
  signIn: string[];
  completeSignIn: string[];
  signOut: number;
  authorizeRequest: number;
  invalidateIfCurrent: number;
  /** Subscriber notifications actually delivered. */
  notified: number;
  listenerAdds: number;
  listenerRemoves: number;
}

/**
 * Implements the full internal port, credential capability included. That is
 * deliberate: a provider test can inject this and then assert that what reaches
 * the React tree still has no `authorizeRequest` on it.
 */
export interface FakeAuthClient extends CredentialAuthClient {
  readonly calls: FakeAuthClientCalls;
  /** Live subscriber count, for listener add/remove balance assertions. */
  listenerCount(): number;
  emitSessionInvalidated(): void;
  /** Makes the next initialize() hang until the returned deferred settles. */
  deferInitialize(): Deferred<InitializeResult>;
  /** Makes the next completeSignIn() hang until the deferred settles. */
  deferCompleteSignIn(): Deferred<CompleteSignInResult>;
  failInitialize(): void;
  failSignIn(): void;
}

export interface FakeAuthClientOptions {
  readonly initialSession?: AuthSession | null;
  readonly completeSignInResult?: CompleteSignInResult;
  /**
   * Stands in for the adapter's in-memory user store. `null` means "signed in
   * but nothing to authorize with", which is exactly the state that must
   * produce a local failure rather than an unauthenticated network call.
   */
  readonly accessToken?: string | null;
}

export function createFakeAuthClient(options: FakeAuthClientOptions = {}): FakeAuthClient {
  const { initialSession = null } = options;
  const listeners = new Set<() => void>();
  const calls: FakeAuthClientCalls = {
    initialize: 0,
    initializeWork: 0,
    signIn: [],
    completeSignIn: [],
    signOut: 0,
    authorizeRequest: 0,
    invalidateIfCurrent: 0,
    notified: 0,
    listenerAdds: 0,
    listenerRemoves: 0,
  };

  // Mirrors the adapter: a session that is already gone cannot be invalidated
  // again, so repeated invalidation notifies subscribers exactly once, and each
  // published session gets a fresh opaque identity.
  let sessionLive = initialSession !== null;
  let ownership: object | null = sessionLive ? Object.freeze({}) : null;
  const accessToken = options.accessToken ?? "fake.access.token";

  let inFlightInitialize: Promise<InitializeResult> | undefined;
  let initializeDeferred: Deferred<InitializeResult> | undefined;
  let completeSignInDeferred: Deferred<CompleteSignInResult> | undefined;
  let initializeShouldFail = false;
  let signInShouldFail = false;

  const defaultCompleteResult: CompleteSignInResult = options.completeSignInResult ?? {
    session: { subject: "11111111-1111-4111-8111-111111111111", displayName: "Test Analyst" },
    returnTo: "/",
  };

  return {
    calls,

    listenerCount(): number {
      return listeners.size;
    },

    emitSessionInvalidated(): void {
      for (const listener of [...listeners]) {
        listener();
      }
    },

    deferInitialize(): Deferred<InitializeResult> {
      const deferred = createDeferred<InitializeResult>();
      initializeDeferred = deferred;
      return deferred;
    },

    deferCompleteSignIn(): Deferred<CompleteSignInResult> {
      const deferred = createDeferred<CompleteSignInResult>();
      completeSignInDeferred = deferred;
      return deferred;
    },

    failInitialize(): void {
      initializeShouldFail = true;
    },

    failSignIn(): void {
      signInShouldFail = true;
    },

    initialize(): Promise<InitializeResult> {
      calls.initialize += 1;
      // Mirrors the real adapter: concurrent callers share one run, and the
      // entry is released on settle so a genuine later call runs again.
      if (inFlightInitialize === undefined) {
        calls.initializeWork += 1;
        const run =
          initializeDeferred !== undefined
            ? initializeDeferred.promise
            : initializeShouldFail
              ? Promise.reject(new Error("initialize failed"))
              : Promise.resolve<InitializeResult>({ session: initialSession });
        inFlightInitialize = run.finally(() => {
          inFlightInitialize = undefined;
        });
      }
      return inFlightInitialize;
    },

    signIn(returnTo: string): Promise<void> {
      calls.signIn.push(returnTo);
      if (signInShouldFail) {
        return Promise.reject(new Error("sign-in failed"));
      }
      return Promise.resolve();
    },

    completeSignIn(callbackUrl: string): Promise<CompleteSignInResult> {
      calls.completeSignIn.push(callbackUrl);
      sessionLive = true;
      ownership = Object.freeze({});
      if (completeSignInDeferred !== undefined) {
        const deferred = completeSignInDeferred;
        completeSignInDeferred = undefined;
        return deferred.promise;
      }
      return Promise.resolve(defaultCompleteResult);
    },

    signOut(): Promise<void> {
      calls.signOut += 1;
      sessionLive = false;
      ownership = null;
      return Promise.resolve();
    },

    /**
     * Returns a copy carrying the credential plus a callback scoped to the
     * session that issued it; the caller's request is never given an
     * Authorization header of its own and the token is never returned.
     */
    authorizeRequest(request: Request): Promise<AuthorizedRequest | null> {
      calls.authorizeRequest += 1;
      const issuedFor = ownership;
      if (!sessionLive || issuedFor === null || accessToken === null || accessToken === "") {
        return Promise.resolve(null);
      }
      const headers = new Headers(request.headers);
      headers.delete("Authorization");
      headers.set("Authorization", `Bearer ${accessToken}`);
      return Promise.resolve({
        request: new Request(request, { headers }),
        invalidateIfCurrent: () => {
          calls.invalidateIfCurrent += 1;
          if (ownership !== issuedFor || !sessionLive) {
            return;
          }
          sessionLive = false;
          ownership = null;
          for (const listener of [...listeners]) {
            calls.notified += 1;
            listener();
          }
        },
      });
    },

    onSessionInvalidated(listener: () => void): () => void {
      calls.listenerAdds += 1;
      listeners.add(listener);
      return () => {
        calls.listenerRemoves += 1;
        listeners.delete(listener);
      };
    },
  };
}
