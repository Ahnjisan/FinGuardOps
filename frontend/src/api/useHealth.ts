import { useCallback, useEffect, useRef, useState } from "react";
import type { AsyncState } from "../shared/asyncState";
import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";
import { fetchHealth } from "./healthApi";
import type { HealthResult } from "./types";

export type HealthErrorKind = "timeout" | "network" | "http" | "invalid-response" | "unknown";

export interface UseHealthResult {
  readonly state: AsyncState<HealthResult, HealthErrorKind>;
  readonly retry: () => void;
}

function classifyError(error: unknown): HealthErrorKind {
  if (error instanceof TimeoutError) {
    return "timeout";
  }
  if (error instanceof NetworkError) {
    return "network";
  }
  if (error instanceof HttpError) {
    return "http";
  }
  if (error instanceof InvalidResponseError) {
    return "invalid-response";
  }
  return "unknown";
}

interface HealthRequestEntry {
  readonly controller: AbortController;
  promise: Promise<HealthResult>;
  subscriberCount: number;
  settled: boolean;
}

/**
 * Module-level in-flight request registry, keyed by reference-counted
 * subscribers rather than a boolean flag. Shares one underlying request
 * across concurrent callers (e.g. React StrictMode's setup->cleanup->setup
 * double-invoke of the same logical mount) so the network call fires once.
 * No permanent success/error cache: the entry is removed as soon as it
 * settles, so a genuine later remount always starts a fresh request.
 */
let currentEntry: HealthRequestEntry | undefined;

function createHealthRequestEntry(): HealthRequestEntry {
  const controller = new AbortController();
  const entry: HealthRequestEntry = {
    controller,
    subscriberCount: 0,
    settled: false,
    // Assigned immediately below; only `entry` itself needs to exist first
    // so the .finally() callback can compare identity against it.
    promise: undefined as unknown as Promise<HealthResult>,
  };
  entry.promise = fetchHealth(controller.signal).finally(() => {
    entry.settled = true;
    // An entry that was already detached by a deferred abort (see
    // releaseEntry below) must not clobber a newer entry that may have
    // taken its place in the registry by the time this settles.
    if (currentEntry === entry) {
      currentEntry = undefined;
    }
  });
  return entry;
}

function acquireEntry(): HealthRequestEntry {
  if (!currentEntry) {
    currentEntry = createHealthRequestEntry();
  }
  currentEntry.subscriberCount += 1;
  return currentEntry;
}

/**
 * Releases one subscriber's claim on an entry. When the last subscriber
 * goes away, cancellation is deferred to a microtask rather than applied
 * immediately: React StrictMode's cleanup->setup replay re-acquires the
 * same entry synchronously within the same turn, before this microtask
 * runs, so the recheck below sees a non-zero subscriber count and the
 * request survives. A genuine unmount has no such replay, so the recheck
 * still sees zero subscribers and the request is actually aborted. No
 * setTimeout/grace-period heuristics are used — only true microtask
 * ordering, which is what makes the StrictMode replay reliably win the race.
 */
function releaseEntry(entry: HealthRequestEntry): void {
  if (entry.subscriberCount <= 0) {
    return;
  }
  entry.subscriberCount -= 1;
  if (entry.subscriberCount > 0) {
    return;
  }

  queueMicrotask(() => {
    if (entry.subscriberCount > 0) {
      return; // a new subscriber joined before this ran (StrictMode replay)
    }
    if (entry.settled) {
      return; // nothing left to abort
    }
    if (currentEntry !== entry) {
      return; // this entry was already superseded/cleared
    }
    // Detach immediately so a subscriber that acquires right after this
    // point can never be handed a dying entry, even before the underlying
    // fetch has actually rejected from the abort below.
    currentEntry = undefined;
    entry.controller.abort();
  });
}

/**
 * Subscribes to the shared health request, acquiring a registry entry and
 * delivering its outcome to at most one of onSuccess/onError — and only
 * while the returned unsubscribe function has not yet been called. The
 * production hook's effect cleanup calls this returned function directly,
 * so removing that wiring (or this function's guards) changes real,
 * externally observable behavior: the request registry's subscriber count,
 * and — once it reaches zero — the AbortSignal passed all the way down to
 * fetch(). Calling unsubscribe more than once is a no-op (idempotent).
 */
export function subscribeToHealthRequest(
  onSuccess: (result: HealthResult) => void,
  onError: (error: unknown) => void,
): () => void {
  const entry = acquireEntry();
  let active = true;
  let released = false;

  entry.promise.then(
    (result) => {
      if (!active) {
        return;
      }
      active = false;
      onSuccess(result);
    },
    (error: unknown) => {
      if (!active) {
        return;
      }
      active = false;
      onError(error);
    },
  );

  return () => {
    if (released) {
      return;
    }
    released = true;
    active = false;
    releaseEntry(entry);
  };
}

export function useHealth(): UseHealthResult {
  const [state, setState] = useState<AsyncState<HealthResult, HealthErrorKind>>({
    status: "loading",
  });
  const [attempt, setAttempt] = useState(0);
  const requestInFlightRef = useRef(false);

  useEffect(() => {
    requestInFlightRef.current = true;

    const unsubscribe = subscribeToHealthRequest(
      (result) => {
        requestInFlightRef.current = false;
        setState({ status: "success", data: result });
      },
      (error: unknown) => {
        requestInFlightRef.current = false;
        setState({ status: "error", error: classifyError(error) });
      },
    );

    return unsubscribe;
  }, [attempt]);

  const retry = useCallback(() => {
    if (state.status !== "error" || requestInFlightRef.current) {
      return;
    }
    requestInFlightRef.current = true;
    setState({ status: "loading" });
    setAttempt((current) => current + 1);
  }, [state.status]);

  return { state, retry };
}
