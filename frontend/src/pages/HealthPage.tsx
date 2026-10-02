import { useHealth } from "../api/useHealth";
import { Icon } from "../shared/Icon";

const SAFE_ERROR_MESSAGE =
  "백엔드 상태를 확인할 수 없습니다. 다시 시도하세요.";

export function HealthPage() {
  const { state, retry } = useHealth();

  return (
    <section aria-labelledby="health-heading">
      <h2 id="health-heading">백엔드 상태</h2>
      <div role="status">
        {state.status === "loading" && <p>백엔드 상태를 확인하고 있습니다…</p>}
        {state.status === "success" && <p>백엔드가 정상적으로 응답합니다.</p>}
        {state.status === "error" && <p>{SAFE_ERROR_MESSAGE}</p>}
      </div>
      {state.status === "error" && (
        <button className="button" type="button" onClick={retry}>
          <Icon name="refresh" />다시 시도
        </button>
      )}
    </section>
  );
}
