import { useState } from "react";
import { Link } from "react-router-dom";
import { fetchDlq, decideDlq, type DlqCoordinate, type DlqDiagnostic } from "../api/aiDlqApi";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useCapabilities } from "../auth/useCapabilities";

export function AiDlqRecoveryPage() {
  const capabilities = useCapabilities();
  const [topicId, setTopicId] = useState("");
  const [partition, setPartition] = useState("");
  const [offset, setOffset] = useState("");
  const [data, setData] = useState<DlqDiagnostic | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [coordinate, setCoordinate] = useState<DlqCoordinate | null>(null);

  async function inspect(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setData(null);
    setError("");
    const validId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(topicId);
    const validNumber = (value: string) => /^(0|[1-9][0-9]*)$/.test(value) &&
      Number.isSafeInteger(Number(value));
    if (!validId || !validNumber(partition) || !validNumber(offset) || Number(partition) > 2147483647) {
      setError("DLQ 좌표 형식을 확인하세요.");
      return;
    }
    const selected = { topicId, partition: Number(partition), offset: Number(offset) };
    setCoordinate(selected);
    setBusy(true);
    try { setData(await fetchDlq(getOidcAuthClient(), selected)); }
    catch { setError("단건 진단에 실패했습니다. broker와 좌표를 확인하세요."); }
    finally { setBusy(false); }
  }

  async function act(action: "quarantine" | "replay") {
    if (!data || busy || !capabilities.has("ai-dlq:action")) return;
    setBusy(true);
    setError("");
    try { setData(await decideDlq(getOidcAuthClient(), data, action)); }
    catch { setError("조치 조건이 바뀌었거나 요청에 실패했습니다. 다시 조회하세요."); }
    finally { setBusy(false); }
  }

  async function refresh() {
    if (!coordinate || busy) return;
    setBusy(true);
    setError("");
    try { setData(await fetchDlq(getOidcAuthClient(), coordinate)); }
    catch { setError("최신 상태를 확인하지 못했습니다."); }
    finally { setBusy(false); }
  }

  return <section className="ai-operations" aria-labelledby="ai-dlq-title">
    <Link to="/ai-operations">AI 운영</Link><h2 id="ai-dlq-title">AI 리포트 DLQ 단건 복구</h2>
    <form onSubmit={(event) => void inspect(event)}>
      <label>DLQ topic ID<input value={topicId} onChange={(event) => setTopicId(event.target.value)} /></label>
      <label>Partition<input value={partition} onChange={(event) => setPartition(event.target.value)} /></label>
      <label>Offset<input value={offset} onChange={(event) => setOffset(event.target.value)} /></label>
      <button type="submit" disabled={busy}>단건 진단</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {data && <section aria-label="DLQ diagnostic">
      <dl>
        <div><dt>실패 분류</dt><dd>{data.failureCategory}</dd></div>
        <div><dt>출처 확인</dt><dd>{data.sourceVerified ? "확인" : "미확인"}</dd></div>
        <div><dt>원본 offset 회복</dt><dd>{data.sourceRecovered ? "확인" : "미확인"}</dd></div>
        <div><dt>이벤트 ID</dt><dd>{data.eventId ?? "미확인"}</dd></div>
        <div><dt>실행 ID</dt><dd>{data.executionId ?? "미확인"}</dd></div>
        <div><dt>실행 상태</dt><dd>{data.executionStatus ?? "미확인"}</dd></div>
        <div><dt>거부 사유</dt><dd>{data.rejectionReason ?? "없음"}</dd></div>
        <div><dt>조치</dt><dd>{data.action ?? "없음"}</dd></div>
        <div><dt>DB 발행 의도</dt><dd>{data.dispatchStatus ?? "없음"}</dd></div>
        <div><dt>broker 발행 좌표</dt><dd>{data.ackOffset === null ? "미확인" : `${data.ackPartition}:${data.ackOffset}`}</dd></div>
        <div><dt>업무 시작 경로</dt><dd>{data.startSource ?? "미확인"}</dd></div>
        <div><dt>리포트</dt><dd>{data.reportExists ? "저장됨" : "없음"}</dd></div>
        <div><dt>기록된 Provider attempt</dt><dd>{data.attemptExists ? "있음" : "없음 또는 미기록"}</dd></div>
      </dl>
      {capabilities.has("ai-dlq:action") && data.action === null && <>
        <button type="button" disabled={busy} onClick={() => void act("quarantine")}>명시적 격리</button>
        <button type="button" disabled={busy || !data.replayAllowed}
          onClick={() => void act("replay")}>조건부 단건 재처리</button>
      </>}
      <button type="button" disabled={busy} onClick={() => void refresh()}>최신 상태 다시 조회</button>
      <p>승인은 DB 의도만 기록합니다. broker 발행, Kafka 또는 polling 완료, 리포트 저장을 각각 확인하세요.</p>
    </section>}
  </section>;
}
