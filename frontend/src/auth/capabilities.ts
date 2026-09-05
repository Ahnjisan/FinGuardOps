import type { UserRole } from "./userRoles";

/**
 * What the UI may offer, expressed as things this frontend can actually reach
 * today rather than as a copy of the Backend authority list.
 *
 * Each capability stands for a group of approved Backend endpoints in
 * `src/api/backendEndpoints.ts`, which is itself the exact USER subset of
 * `docs/02-architecture/security-architecture.md` section 5:
 *
 * - `transaction:view`  -> transaction-list, transaction-detail
 * - `case:view`         -> case-list, case-detail, case-note-list, case-audit-list
 * - `case:workflow`     -> case-status-change, case-assignee-change
 * - `case:note-write`   -> case-note-create
 * - `case:resolve`      -> case-resolution-create
 *
 * Backend authorities with no reachable endpoint get no capability at all.
 * `rule-version:*`, `recovery:*`, `platform:*`, `ai-operations:*`,
 * `ai-usage:*`, `ai-report:*`, `behavior-event:read` and `detection:read` are
 * therefore absent: some have no Spring controller, the rest have no endpoint
 * key in this client. Inventing a capability for them would describe an
 * unimplemented screen as if it existed.
 */
export type UiCapability =
  | "transaction:view"
  | "case:view"
  | "case:workflow"
  | "case:note-write"
  | "case:resolve";

/**
 * Canonical order, used to render a decided set deterministically. Role arrays
 * carry no meaningful order, so the union of two roles must not depend on which
 * role the Authorization Server happened to list first.
 */
export const UI_CAPABILITIES: readonly UiCapability[] = Object.freeze<UiCapability[]>([
  "transaction:view",
  "case:view",
  "case:workflow",
  "case:note-write",
  "case:resolve",
]);

/**
 * The role table, keyed by a `Map` so that a name reaching here from untyped
 * data cannot resolve to an inherited property.
 *
 * `PLATFORM_ADMIN` maps to nothing on purpose. Section 4 of the security
 * architecture states it does not inherit case or transaction authority, and
 * every authority it does hold belongs to an endpoint this client cannot call.
 * `RULE_OPERATOR` and `RECOVERY_OPERATOR` are empty for the same reason: rule
 * publication and recovery execution are not HTTP endpoints at all.
 */
const ROLE_CAPABILITIES: ReadonlyMap<UserRole, readonly UiCapability[]> = new Map<
  UserRole,
  readonly UiCapability[]
>([
  ["FDS_VIEWER", Object.freeze<UiCapability[]>(["transaction:view", "case:view"])],
  [
    "FDS_ANALYST",
    Object.freeze<UiCapability[]>([
      "transaction:view",
      "case:view",
      "case:workflow",
      "case:note-write",
    ]),
  ],
  [
    "FDS_APPROVER",
    Object.freeze<UiCapability[]>(["transaction:view", "case:view", "case:resolve"]),
  ],
  ["RULE_OPERATOR", Object.freeze<UiCapability[]>([])],
  ["RECOVERY_OPERATOR", Object.freeze<UiCapability[]>([])],
  ["PLATFORM_ADMIN", Object.freeze<UiCapability[]>([])],
]);

/**
 * A decided capability set.
 *
 * The backing `Set` stays captured in the closure below and is never handed
 * out, so a holder can ask questions of a decision but cannot widen it.
 * `granted` is a frozen array kept for rendering and assertions.
 */
export interface CapabilitySet {
  has(capability: UiCapability): boolean;
  readonly granted: readonly UiCapability[];
}

function createCapabilitySet(granted: ReadonlySet<UiCapability>): CapabilitySet {
  const ordered = Object.freeze(UI_CAPABILITIES.filter((capability) => granted.has(capability)));
  return Object.freeze({
    has: (capability: UiCapability): boolean => granted.has(capability),
    granted: ordered,
  });
}

/**
 * The answer for every state that is not an authenticated USER: still
 * initializing, unauthenticated, or in error. A single shared frozen value, so
 * consumers that memoize on identity do not re-render as the session comes and
 * goes.
 */
export const NO_CAPABILITIES: CapabilitySet = createCapabilitySet(new Set<UiCapability>());

/**
 * Derives the capability set of a session from its already validated roles.
 *
 * Multiple roles produce the union of their capabilities, matching the Backend
 * rule that a principal holds the union of its roles' authorities. `undefined`
 * is the fail-closed answer for the states that have no session to ask about,
 * and it is what the hook passes while authentication is still undecided.
 *
 * The missing and empty cases are handled rather than excluded by the
 * signature. A published session cannot carry either — `resolveUserRoles()`
 * refuses both before a session exists — so these branches are unreachable
 * through the type. They stay because a permission decision has to hold on an
 * input the type says cannot occur, and the failure mode of dropping them is
 * granting everything to a value that arrived from outside the type system.
 *
 * A name with no table entry contributes nothing instead of throwing, for the
 * same reason: "unrecognized" must never mean "unrestricted".
 */
export function resolveCapabilities(roles: readonly UserRole[] | undefined): CapabilitySet {
  if (roles === undefined || roles.length === 0) {
    return NO_CAPABILITIES;
  }

  const granted = new Set<UiCapability>();
  for (const role of roles) {
    const capabilities = ROLE_CAPABILITIES.get(role);
    if (capabilities === undefined) {
      continue;
    }
    for (const capability of capabilities) {
      granted.add(capability);
    }
  }
  return createCapabilitySet(granted);
}
