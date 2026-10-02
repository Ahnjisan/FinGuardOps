import type { ReactNode } from "react";
import type { UiCapability } from "../auth/capabilities";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import { AccessDeniedPage } from "../pages/AccessDeniedPage";

/**
 * A convenience boundary, not an authorization boundary.
 *
 * Hiding a route changes what a person is offered; it does not change what the
 * Backend will do. Every protected endpoint re-decides the same question from
 * the access token on every request and answers 401 or 403, and that answer is
 * final. This component must never be cited as the reason an endpoint is safe.
 */
export interface RequireCapabilityProps {
  readonly capability: UiCapability;
  readonly children: ReactNode;
}

export function RequireCapability({ capability, children }: RequireCapabilityProps) {
  const { state } = useAuth();
  const capabilities = useCapabilities();

  // Not decided yet. A session may still be restored or completed, so this must
  // not resolve to a refusal: showing "access denied" here and the content a
  // moment later would teach users to reload past a real refusal.
  if (state.status === "initializing" || state.status === "authenticating") {
    return (
      <div role="status">
        <p>접근 권한을 확인하고 있습니다…</p>
      </div>
    );
  }

  // No session at all. `error` joins this branch rather than getting a message
  // of its own: the reason authentication is unavailable belongs to the shell's
  // status region, which already reports it, and repeating a fixed error here
  // would say nothing more. `signing-out` joins it too: the local session, its
  // ownership and its capabilities are already gone by the time that state
  // exists, so the guarded content must go with them rather than linger until
  // the end-session redirect lands.
  if (
    state.status === "unauthenticated" ||
    state.status === "signing-out" ||
    state.status === "error"
  ) {
    return (
      <section aria-labelledby="sign-in-required-heading">
        <h2 id="sign-in-required-heading">로그인이 필요합니다</h2>
        <p>이 화면을 보려면 로그인하세요.</p>
      </section>
    );
  }

  // Signed in and decided. A USER whose roles grant nothing reachable — every
  // holder of only `RULE_OPERATOR`, `RECOVERY_OPERATOR` or `PLATFORM_ADMIN`
  // today — lands here, which is an ordinary outcome rather than a fault.
  if (!capabilities.has(capability)) {
    return <AccessDeniedPage />;
  }

  return <>{children}</>;
}
