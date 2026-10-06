import { useEffect, useState } from "react";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import type { AuthSession } from "../auth/authClient";
import { fetchAiRequestDetail, fetchAiUsageList, fetchAiUsageSummary,
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
        provider: query.provider, model: query.model, reportStatus: query.reportStatus,
        reportSource: query.reportSource, cacheHit: query.cacheHit, fallbackUsed: query.fallbackUsed };
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
