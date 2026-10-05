import { useCallback, useEffect, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { fetchAdoptedDetection } from "./adoptedDetectionApi";
import { createAiReport, fetchAiReportCurrent, type AiReportCurrent } from "./aiReportApi";
import { fetchCaseTransactionPage } from "./caseTransactionApi";

interface Snapshot {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly generation: number;
  readonly current: AiReportCurrent | null;
  readonly eligibleVersion: number | null;
  readonly error: string | null;
  readonly busy: boolean;
}

export function useAiReport(caseId: string, caseStatus: string) {
  const { state: auth } = useAuth();
  const capabilities = useCapabilities();
  const session = auth.status === "authenticated" && capabilities.has("ai-report:view") ? auth.session : null;
  const [generation, setGeneration] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const selected = snapshot !== null && snapshot.session === session && snapshot.caseId === caseId &&
    snapshot.generation === generation ? snapshot : null;

  useEffect(() => {
    if (session === null) return;
    let active = true;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (!active) return;
      fetchAiReportCurrent(getOidcAuthClient(), caseId, controller.signal).then(async (current) => {
        let eligibleVersion: number | null = null;
        if (caseStatus === "IN_REVIEW" && capabilities.has("ai-report:create")) {
          try {
            const page = await fetchCaseTransactionPage(getOidcAuthClient(), caseId,
              { page: 0, size: 2 }, controller.signal);
            if (page.page.totalElements === 1 && page.content.length === 1) {
              const adopted = await fetchAdoptedDetection(getOidcAuthClient(),
                page.content[0].transactionId, controller.signal);
              if (adopted.availability === "AVAILABLE" && adopted.adoptedResult !== null &&
                  ["HIGH", "CRITICAL"].includes(adopted.adoptedResult.riskLevel)) {
                eligibleVersion = adopted.adoptedResult.detectionResultVersion;
              }
            }
          } catch {
            // Report reading remains available when eligibility evidence cannot be read.
          }
        }
        if (active) setSnapshot({ session, caseId, generation, current, eligibleVersion, error: null, busy: false });
      }).catch(() => {
        if (active) setSnapshot({ session, caseId, generation, current: null, eligibleVersion: null,
          error: "AI 리포트 정보를 조회하지 못했습니다.", busy: false });
      });
    });
    return () => { active = false; controller.abort(); };
  }, [session, caseId, caseStatus, capabilities, generation]);

  const refresh = useCallback(() => setGeneration((value) => value + 1), []);
  const create = useCallback(async () => {
    if (selected === null || session === null || caseStatus !== "IN_REVIEW" ||
        !capabilities.has("ai-report:create") || selected.eligibleVersion === null || selected.busy) return;
    setSnapshot({ ...selected, busy: true, error: null });
    try {
      await createAiReport(getOidcAuthClient(), caseId, selected.eligibleVersion, crypto.randomUUID());
      refresh();
    } catch {
      setSnapshot({ ...selected, busy: false, error: "생성 요청 결과를 확인하지 못했습니다. 새로고침해 상태를 확인하세요." });
    }
  }, [selected, session, caseId, caseStatus, capabilities, refresh]);
  return { state: selected, refresh, create, canCreate: session !== null &&
    capabilities.has("ai-report:create") && caseStatus === "IN_REVIEW" &&
    selected !== null && selected.eligibleVersion !== null };
}
