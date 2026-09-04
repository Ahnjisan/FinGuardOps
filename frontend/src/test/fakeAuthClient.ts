import type {
  AuthClient,
  AuthSession,
  CompleteSignInResult,
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
  listenerAdds: number;
  listenerRemoves: number;
}

export interface FakeAuthClient extends AuthClient {
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
    listenerAdds: 0,
    listenerRemoves: 0,
  };

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
      if (completeSignInDeferred !== undefined) {
        const deferred = completeSignInDeferred;
        completeSignInDeferred = undefined;
        return deferred.promise;
      }
      return Promise.resolve(defaultCompleteResult);
    },

    signOut(): Promise<void> {
      calls.signOut += 1;
      return Promise.resolve();
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
