import { useEffect, useState } from "react";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import type { AuthSession } from "../auth/authClient";
import { ForbiddenError, HttpError } from "./errors";
import { fetchAiRequestDetail, fetchAiUsageList, fetchAiUsageSummary,
  fetchAiOutboxDiagnostic, requeueAiOutbox, type AiOutboxDiagnostic,
  type AiUsageDetail, type AiUsageFilters, type AiUsageList, type AiUsageListQuery,
  type AiUsageSummary } from "./aiOperationsApi";

type Result<T> = { loading: boolean; data: T | null; error: boolean;
  session?: AuthSession; key?: string };
const EMPTY = { loading: true, data: null, error: false } as const;

export function useAiUsage(query: AiUsageListQuery) {
  const { state } = useAuth();
  const capabilities = useCapabilities();
  const session = state.status === "authenticated" && capabilities.has("ai-usage:view")
    ? state.session : null;
  const key = JSON.stringify(query);
  const [list, setList] = useState<Result<AiUsageList>>(EMPTY);
  const [summary, setSummary] = useState<Result<AiUsageSummary>>(EMPTY);
  useEffect(() => {
    if (session === null) return;
    const controller = new AbortController();
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      setList(EMPTY); setSummary(EMPTY);
      const client = getOidcAuthClient();
      fetchAiUsageList(client, query, controller.signal).then((data) => {
        if (active) setList({ loading: false, data, error: false, session, key });
      }).catch(() => { if (active) setList({ loading: false, data: null, error: true, session, key }); });
      const filters: AiUsageFilters = { from: query.from, to: query.to,
        ...(query.provider !== undefined ? { provider: query.provider } : {}),
        ...(query.model !== undefined ? { model: query.model } : {}),
        ...(query.reportStatus !== undefined ? { reportStatus: query.reportStatus } : {}),
        ...(query.reportSource !== undefined ? { reportSource: query.reportSource } : {}),
        ...(query.cacheHit !== undefined ? { cacheHit: query.cacheHit } : {}),
        ...(query.fallbackUsed !== undefined ? { fallbackUsed: query.fallbackUsed } : {}) };
      fetchAiUsageSummary(client, filters, controller.signal).then((data) => {
        if (active) setSummary({ loading: false, data, error: false, session, key });
      }).catch(() => { if (active) setSummary({ loading: false, data: null, error: true, session, key }); });
    });
    return () => { active = false; controller.abort(); };
  // The serialized key is the committed query identity.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session, key]);
  return { list: session !== null && list.session === session && list.key === key ? list : EMPTY,
    summary: session !== null && summary.session === session && summary.key === key ? summary : EMPTY };
}

export function useAiRequestDetail(aiRequestId: string) {
  const { state } = useAuth();
  const capabilities = useCapabilities();
  const session = state.status === "authenticated" && capabilities.has("ai-operations:view")
    ? state.session : null;
  const [result, setResult] = useState<Result<AiUsageDetail>>(EMPTY);
  useEffect(() => {
    if (session === null) return;
    const controller = new AbortController();
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      setResult(EMPTY);
      fetchAiRequestDetail(getOidcAuthClient(), aiRequestId, controller.signal).then((data) => {
        if (active) setResult({ loading: false, data, error: false, session, key: aiRequestId });
      }).catch(() => { if (active) setResult({ loading: false, data: null, error: true,
        session, key: aiRequestId }); });
    });
    return () => { active = false; controller.abort(); };
  }, [session, aiRequestId]);
  return session !== null && result.session === session && result.key === aiRequestId ? result : EMPTY;
}

export function useAiOutbox(executionId: string | null) {
  const { state } = useAuth();
  const capabilities = useCapabilities();
  const session = state.status === "authenticated" && capabilities.has("ai-operations:view")
    ? state.session : null;
  const canRequeue = session !== null && capabilities.has("ai-outbox:requeue");
  const [data, setData] = useState<AiOutboxDiagnostic | null>(null);
  const [error, setError] = useState<"forbidden" | "conflict" | "not-found" | "other" | null>(null);
  const [busy, setBusy] = useState(false);
  const [acceptedExecutionId, setAcceptedExecutionId] = useState<string | null>(null);
  const [priorFailure, setPriorFailure] = useState<{ executionId: string; code: string | null } | null>(null);
  const accepted = acceptedExecutionId === executionId && executionId !== null;
  const visibleData = data?.executionId === executionId ? data : null;
  const [refreshKey, setRefreshKey] = useState(0);
  useEffect(() => {
    if (session === null || executionId === null) return;
    const controller = new AbortController();
    let active = true;
    fetchAiOutboxDiagnostic(getOidcAuthClient(), executionId, controller.signal)
      .then((value) => { if (active) { setData(value); setError(null); } })
      .catch((failure: unknown) => {
        if (!active) return;
        if (failure instanceof ForbiddenError) { setData(null); setError("forbidden"); }
        else if (failure instanceof HttpError && failure.status === 404) {
          setData(null); setError("not-found");
        } else setError("other");
      });
    return () => { active = false; controller.abort(); };
  }, [session, executionId, refreshKey]);
  useEffect(() => {
    if (!accepted || executionId === null) return;
    const timer = window.setInterval(() => setRefreshKey((key) => key + 1), 5000);
    return () => window.clearInterval(timer);
  }, [accepted, executionId]);
  async function requeue() {
    if (!canRequeue || visibleData === null || !visibleData.requeueAllowed || busy || error !== null) return;
    setBusy(true);
    try {
      const result = await requeueAiOutbox(getOidcAuthClient(), visibleData);
      setPriorFailure({ executionId: result.executionId, code: visibleData.failureCode });
      setData(result); setAcceptedExecutionId(result.executionId); setError(null);
    } catch (failure) {
      if (failure instanceof ForbiddenError) setError("forbidden");
      else if (failure instanceof HttpError && failure.status === 409) setError("conflict");
      else setError("other");
      setRefreshKey((key) => key + 1);
    } finally { setBusy(false); }
  }
  return { data: visibleData, error, busy, accepted,
    priorFailureCode: priorFailure?.executionId === executionId ? priorFailure.code : null,
    canRequeue, requeue,
    refresh: () => setRefreshKey((key) => key + 1) };
}
