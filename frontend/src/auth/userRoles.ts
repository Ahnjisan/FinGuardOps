/**
 * The USER half of the FinGuardOps role contract, as a browser client is
 * allowed to observe it.
 *
 * These six names are the whole USER allowlist from
 * `docs/02-architecture/security-architecture.md` section 4 and
 * `FinGuardOpsRole` on the Backend. The two SERVICE roles
 * (`TRANSACTION_INGESTOR`, `BEHAVIOR_INGESTOR`) are deliberately absent: a
 * SERVICE principal completes `client_credentials` and is issued no ID token at
 * all, so a SERVICE name arriving in a browser session is an anomaly. It is
 * refused here as an unknown role rather than being given a case of its own.
 */
export type UserRole =
  | "FDS_VIEWER"
  | "FDS_ANALYST"
  | "FDS_APPROVER"
  | "RULE_OPERATOR"
  | "RECOVERY_OPERATOR"
  | "PLATFORM_ADMIN";

/** The only `principal_type` a browser session may carry, compared exactly. */
export const USER_PRINCIPAL_TYPE = "USER";

export const USER_ROLES: readonly UserRole[] = Object.freeze<UserRole[]>([
  "FDS_VIEWER",
  "FDS_ANALYST",
  "FDS_APPROVER",
  "RULE_OPERATOR",
  "RECOVERY_OPERATOR",
  "PLATFORM_ADMIN",
]);

/**
 * A decided role set, which is never empty.
 *
 * The tuple shape is the contract rather than a convenience: a session with no
 * role is not a weaker session, it is a login this client refuses to complete,
 * so "at least one role" belongs in the type that a session is built from. A
 * caller holding this value does not have to ask whether the array has
 * anything in it, and a caller trying to build one out of nothing cannot.
 */
export type NonEmptyUserRoles = readonly [UserRole, ...UserRole[]];

/**
 * Membership is a `Set` lookup rather than an object property read, so
 * `"__proto__"`, `"constructor"` and `"toString"` are ordinary strings that are
 * simply not members instead of names that resolve to something truthy on the
 * prototype chain.
 */
const USER_ROLE_NAMES: ReadonlySet<string> = new Set<string>(USER_ROLES);

export function isUserRole(value: unknown): value is UserRole {
  return typeof value === "string" && USER_ROLE_NAMES.has(value);
}

/**
 * Decides the USER roles of a session from the claims an OIDC client has
 * already validated, or refuses the whole claim set.
 *
 * Nothing is normalized on the way through. The value is not trimmed, not case
 * folded, not deduplicated and not filtered down to the names that happen to be
 * recognized: `" FDS_ANALYST "`, `"fds_analyst"`, a repeated role and a single
 * unknown name among five valid ones all produce `null`. That is deliberate
 * rather than strict for its own sake — Backend's `FinGuardOpsJwtValidator`
 * rejects exactly these token shapes with 401, so salvaging a subset here would
 * put controls on screen that are certain to fail the moment they are used.
 *
 * An empty `roles` array is refused for the same reason, not as a stricter
 * extra rule. Backend derives every authority from the role claim and answers
 * 401 to a USER token that carries none, so a browser session built on one
 * could reach no endpoint at all: it would be a login that looks successful and
 * fails on contact with the first request. Refusing it here keeps the two sides
 * answering the same question the same way.
 *
 * `null` is the single failure answer and means "do not publish a session".
 * The caller turns it into a refused sign-in rather than a session holding
 * nothing.
 *
 * The returned tuple is frozen and is the value stored on the session, so no
 * later holder can add a role to it.
 */
export function resolveUserRoles(
  principalType: unknown,
  roles: unknown,
): NonEmptyUserRoles | null {
  // Exact string identity. A missing claim, `null`, a number, an object and
  // `"user"` in any other casing all fail this comparison without a separate
  // type test, and `"SERVICE"` fails it here rather than later.
  if (principalType !== USER_PRINCIPAL_TYPE) {
    return null;
  }
  if (!Array.isArray(roles)) {
    return null;
  }

  // Destructured rather than length-checked so the non-empty tuple is built
  // from a value that was actually validated. An empty claim array leaves
  // `head` as `undefined`, which the same membership test that rejects
  // `"fds_analyst"` rejects here — there is no separate emptiness branch to
  // keep in step with the element rule.
  const [head, ...tail] = roles as readonly unknown[];
  if (!isUserRole(head)) {
    return null;
  }
  const accepted: [UserRole, ...UserRole[]] = [head];
  const seen = new Set<string>([head]);
  for (const candidate of tail) {
    if (!isUserRole(candidate) || seen.has(candidate)) {
      return null;
    }
    seen.add(candidate);
    accepted.push(candidate);
  }
  // Provider order is preserved rather than sorted: ADR-011 defines no canonical
  // role order, so imposing one here would be an invented contract.
  return Object.freeze(accepted);
}
