import { useHealth } from "../api/useHealth";

const SAFE_ERROR_MESSAGE =
  "Unable to reach the backend health check right now. Please try again.";

export function HealthPage() {
  const { state, retry } = useHealth();

  return (
    <section aria-labelledby="health-heading">
      <h2 id="health-heading">Backend Health</h2>
      <div role="status">
        {state.status === "loading" && <p>Checking backend health status...</p>}
        {state.status === "success" && <p>Backend service is healthy.</p>}
        {state.status === "error" && <p>{SAFE_ERROR_MESSAGE}</p>}
      </div>
      {state.status === "error" && (
        <button type="button" onClick={retry}>
          Retry
        </button>
      )}
    </section>
  );
}
