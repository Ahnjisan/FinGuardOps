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
  deferInitialize(): Deferred<TestInitializeResult>;
  /** Makes the next completeSignIn() hang until the deferred settles. */
  deferCompleteSignIn(): Deferred<TestCompleteSignInResult>;
  failInitialize(): void;
  failSignIn(): void;
}

/**
 * What a test may hand this double as a session: exactly what the port
 * publishes, with no relaxation.
 *
 * Nothing here is optional and nothing is cast. A double that accepted a
 * session the real adapter cannot build would let component tests pass against
 * a shape production never produces, and the shape most worth getting wrong —
 * an authenticated session holding no role — is precisely the one
 * `resolveUserRoles()` refuses. So every caller states a role set it means,
 * and a test that needs the impossible shape builds it locally and says so.
 */
export type TestSessionInput = AuthSession;

export interface TestInitializeResult {
  readonly session: TestSessionInput | null;
}

export interface TestCompleteSignInResult {
  readonly session: TestSessionInput;
  readonly returnTo: unknown;
}

/**
 * Mirrors the adapter's publication rule: a session leaves the port with its
 * roles already frozen. Component tests therefore cannot come to depend on a
 * mutable role array that production would never hand them, and a caller that
 * keeps its fixture and tries to widen a decision after the fact fails here
 * exactly as it would in the browser.
 *
 * The array the test supplied is frozen rather than copied, so the test holds
 * the published value itself and there is no second, still-mutable reference to
 * the same decision.
 */
function toPublishedSession(session: TestSessionInput): AuthSession {
  return { ...session, roles: Object.freeze(session.roles) };
}

function toInitializeResult(result: TestInitializeResult): InitializeResult {
  return {
    session: result.session === null ? null : toPublishedSession(result.session),
  };
}

function toCompleteSignInResult(result: TestCompleteSignInResult): CompleteSignInResult {
  return { ...result, session: toPublishedSession(result.session) };
}

/**
 * Raised when a test drives a callback this double was never told how to
 * answer.
 *
 * The alternative was a stand-in session, which is what this replaces. A double
 * that invents a signed-in user hands every callback test a subject and a role
 * set nobody chose, and the tests that only care about the return route or the
 * address bar silently become tests that also assert a capability. Failing
 * instead keeps "a session exists" something a test says out loud.
 *
 * It is deliberately not an `AuthCallbackError`: this is a test wiring mistake,
 * not the production refusal, and a test asserting the callback failure path
 * must not pass because the fake was left unconfigured.
 */
export class FakeCallbackNotConfiguredError extends Error {
  constructor() {
    super(
      "createFakeAuthClient(): completeSignIn() was called with no callback result. " +
        "Pass completeSignInResult, or arrange one with deferCompleteSignIn(), naming the " +
        "session and its non-empty USER roles.",
    );
    this.name = "FakeCallbackNotConfiguredError";
  }
}

export interface FakeAuthClientOptions {
  /**
   * The session this port starts the page load with, or `null` for none.
   * Supplied whole, with the roles the adapter would have published, so the
   * capability a test depends on is stated by the test rather than inherited
   * from this file.
   */
  readonly initialSession?: TestSessionInput | null;
  /**
   * What a callback resolves to. There is no fallback: omitting this and never
   * calling `deferCompleteSignIn()` makes `completeSignIn()` reject with
   * `FakeCallbackNotConfiguredError` rather than publish a session the test
   * did not describe.
   */
  readonly completeSignInResult?: TestCompleteSignInResult;
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
  let initializeDeferred: Deferred<TestInitializeResult> | undefined;
  let completeSignInDeferred: Deferred<TestCompleteSignInResult> | undefined;
  let initializeShouldFail = false;
  let signInShouldFail = false;

  const publishedSession = initialSession === null ? null : toPublishedSession(initialSession);

  // Only what the caller supplied. Nothing is substituted when it is absent,
  // so no subject and no role reaches a test that did not write one down.
  const configuredCompleteResult: CompleteSignInResult | undefined =
    options.completeSignInResult === undefined
      ? undefined
      : toCompleteSignInResult(options.completeSignInResult);

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

    deferInitialize(): Deferred<TestInitializeResult> {
      const deferred = createDeferred<TestInitializeResult>();
      initializeDeferred = deferred;
      return deferred;
    },

    deferCompleteSignIn(): Deferred<TestCompleteSignInResult> {
      const deferred = createDeferred<TestCompleteSignInResult>();
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
        const run: Promise<InitializeResult> =
          initializeDeferred !== undefined
            ? initializeDeferred.promise.then(toInitializeResult)
            : initializeShouldFail
              ? Promise.reject(new Error("initialize failed"))
              : Promise.resolve<InitializeResult>({ session: publishedSession });
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
        sessionLive = true;
        ownership = Object.freeze({});
        return deferred.promise.then(toCompleteSignInResult);
      }
      // Nothing to publish, so nothing is published: the call is recorded and
      // then rejected before any session becomes live, which is what makes a
      // missing fixture visible instead of convenient.
      if (configuredCompleteResult === undefined) {
        return Promise.reject(new FakeCallbackNotConfiguredError());
      }
      sessionLive = true;
      ownership = Object.freeze({});
      return Promise.resolve(configuredCompleteResult);
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
