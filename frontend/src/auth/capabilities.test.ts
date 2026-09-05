import { describe, expect, it } from "vitest";
import {
  NO_CAPABILITIES,
  resolveCapabilities,
  UI_CAPABILITIES,
  type UiCapability,
} from "./capabilities";
import { isUserRole, resolveUserRoles, USER_ROLES, type UserRole } from "./userRoles";

/**
 * The whole role decision, exercised as a pure function.
 *
 * Both halves live here because they are one rule split across two files: what
 * a claim set is allowed to mean, and what a meaning is allowed to unlock. The
 * cases are written from the refusals outwards, since every interesting failure
 * of a permission UI is something being granted that should not have been.
 */

const VIEWER_CAPABILITIES: readonly UiCapability[] = ["transaction:view", "case:view"];

describe("resolveUserRoles - principal type", () => {
  it("accepts exactly USER", () => {
    expect(resolveUserRoles("USER", ["FDS_ANALYST"])).toEqual(["FDS_ANALYST"]);
  });

  it.each([
    ["a SERVICE principal", "SERVICE"],
    ["an ingestion role name", "TRANSACTION_INGESTOR"],
    ["lowercase", "user"],
    ["mixed case", "User"],
    ["a leading space", " USER"],
    ["a trailing space", "USER "],
    ["a tab", "\tUSER"],
    ["an empty string", ""],
    ["undefined", undefined],
    ["null", null],
    ["a number", 1],
    ["a boolean", true],
    ["an array holding it", ["USER"]],
    ["an object holding it", { principal_type: "USER" }],
    ["a String object", new String("USER")],
  ])("refuses %s", (_label, principalType) => {
    expect(resolveUserRoles(principalType, ["FDS_ANALYST"])).toBeNull();
  });
});

describe("resolveUserRoles - role array", () => {
  it("accepts every USER role", () => {
    expect(resolveUserRoles("USER", [...USER_ROLES])).toEqual([...USER_ROLES]);
  });

  /**
   * Refused rather than accepted as "signed in holding nothing". Backend
   * derives every authority from this claim and answers 401 to a USER token
   * without one, so a session built on an empty array could reach no endpoint
   * at all — a login that looks successful and fails on first contact.
   */
  it("refuses an empty array rather than treating it as a roleless login", () => {
    expect(resolveUserRoles("USER", [])).toBeNull();
  });

  it("refuses an empty array on an otherwise valid USER principal", () => {
    expect(resolveUserRoles("USER", new Array<string>(0))).toBeNull();
  });

  it("preserves provider order rather than imposing one", () => {
    expect(resolveUserRoles("USER", ["PLATFORM_ADMIN", "FDS_VIEWER"])).toEqual([
      "PLATFORM_ADMIN",
      "FDS_VIEWER",
    ]);
  });

  it("freezes what it returns", () => {
    const roles = resolveUserRoles("USER", ["FDS_VIEWER"]);

    expect(Object.isFrozen(roles)).toBe(true);
    // The widening a holder would attempt, reached the only way the readonly
    // tuple allows it to be written. This asserts the runtime guarantee, not
    // the type: a decision must survive a holder that has lost the type.
    expect(() => {
      (roles as unknown as string[]).push("PLATFORM_ADMIN");
    }).toThrow(TypeError);
  });

  it("copies rather than aliases the caller array", () => {
    const claim = ["FDS_VIEWER"];

    const roles = resolveUserRoles("USER", claim);
    claim.push("PLATFORM_ADMIN");

    expect(roles).toEqual(["FDS_VIEWER"]);
  });

  /**
   * One defective element refuses the whole set. Salvaging the recognized names
   * would contradict Backend, which answers 401 for the very same token.
   */
  it.each([
    ["an unknown role", ["SUPER_ADMIN"]],
    ["an unknown role beside valid ones", ["FDS_VIEWER", "FDS_ANALYST", "SUPER_ADMIN"]],
    ["a SERVICE role", ["TRANSACTION_INGESTOR"]],
    ["the other SERVICE role", ["BEHAVIOR_INGESTOR"]],
    ["a SERVICE role mixed with a USER role", ["FDS_ANALYST", "BEHAVIOR_INGESTOR"]],
    ["a Backend authority string", ["case:read"]],
    ["a ROLE_ prefixed name", ["ROLE_FDS_ANALYST"]],
    ["a Keycloak internal role", ["offline_access"]],
    ["another Keycloak internal role", ["uma_authorization"]],
    ["a default realm role", ["default-roles-finguardops-local"]],
    ["a duplicate", ["FDS_ANALYST", "FDS_ANALYST"]],
    ["a duplicate among others", ["FDS_VIEWER", "FDS_ANALYST", "FDS_VIEWER"]],
    ["lowercase", ["fds_analyst"]],
    ["mixed case", ["Fds_Analyst"]],
    ["a leading space", [" FDS_ANALYST"]],
    ["a trailing space", ["FDS_ANALYST "]],
    ["an embedded newline", ["FDS_ANALYST\n"]],
    ["an empty string", [""]],
    ["a prototype property name", ["__proto__"]],
    ["the constructor name", ["constructor"]],
    ["a prototype method name", ["toString"]],
    ["hasOwnProperty", ["hasOwnProperty"]],
    ["a number element", [1]],
    ["a null element", [null]],
    ["an undefined element", [undefined]],
    ["an object element", [{ name: "FDS_ANALYST" }]],
    ["a nested array", [["FDS_ANALYST"]]],
    ["a String object element", [new String("FDS_ANALYST")]],
  ])("refuses %s", (_label, roles) => {
    expect(resolveUserRoles("USER", roles)).toBeNull();
  });

  it.each([
    ["a bare string", "FDS_ANALYST"],
    ["a comma separated string", "FDS_VIEWER,FDS_ANALYST"],
    ["undefined", undefined],
    ["null", null],
    ["a number", 1],
    ["a Set", new Set(["FDS_ANALYST"])],
    ["an array-like object", { 0: "FDS_ANALYST", length: 1 }],
    ["an object keyed by role", { FDS_ANALYST: true }],
  ])("refuses a roles claim that is %s", (_label, roles) => {
    expect(resolveUserRoles("USER", roles)).toBeNull();
  });
});

describe("isUserRole", () => {
  it("recognizes exactly the six USER roles", () => {
    expect(USER_ROLES.filter((role) => isUserRole(role))).toHaveLength(6);
  });

  it.each(["TRANSACTION_INGESTOR", "BEHAVIOR_INGESTOR", "__proto__", "toString", "", "fds_viewer"])(
    "does not recognize %s",
    (value) => {
      expect(isUserRole(value)).toBe(false);
    },
  );
});

describe("resolveCapabilities - one role at a time", () => {
  const PER_ROLE: ReadonlyArray<[UserRole, readonly UiCapability[]]> = [
    ["FDS_VIEWER", VIEWER_CAPABILITIES],
    ["FDS_ANALYST", ["transaction:view", "case:view", "case:workflow", "case:note-write"]],
    ["FDS_APPROVER", ["transaction:view", "case:view", "case:resolve"]],
    ["RULE_OPERATOR", []],
    ["RECOVERY_OPERATOR", []],
    ["PLATFORM_ADMIN", []],
  ];

  it.each(PER_ROLE)("grants %s exactly its reachable capabilities", (role, expected) => {
    expect(resolveCapabilities([role]).granted).toEqual(expected);
  });

  it("covers every USER role", () => {
    expect(PER_ROLE.map(([role]) => role)).toEqual([...USER_ROLES]);
  });

  /**
   * Section 4 of the security architecture: `PLATFORM_ADMIN` inherits neither
   * case nor transaction authority. It is the role most likely to be
   * misremembered as an administrator that can do everything.
   */
  it("gives PLATFORM_ADMIN no case or transaction capability", () => {
    const capabilities = resolveCapabilities(["PLATFORM_ADMIN"]);

    for (const capability of UI_CAPABILITIES) {
      expect(capabilities.has(capability)).toBe(false);
    }
  });

  it("gives a viewer no write capability", () => {
    const capabilities = resolveCapabilities(["FDS_VIEWER"]);

    expect(capabilities.has("case:workflow")).toBe(false);
    expect(capabilities.has("case:note-write")).toBe(false);
    expect(capabilities.has("case:resolve")).toBe(false);
  });

  it("does not let an analyst resolve a case", () => {
    expect(resolveCapabilities(["FDS_ANALYST"]).has("case:resolve")).toBe(false);
  });

  it("does not let an approver change workflow or write notes", () => {
    const capabilities = resolveCapabilities(["FDS_APPROVER"]);

    expect(capabilities.has("case:workflow")).toBe(false);
    expect(capabilities.has("case:note-write")).toBe(false);
  });
});

describe("resolveCapabilities - several roles", () => {
  it("takes the union", () => {
    expect(resolveCapabilities(["FDS_ANALYST", "FDS_APPROVER"]).granted).toEqual([
      "transaction:view",
      "case:view",
      "case:workflow",
      "case:note-write",
      "case:resolve",
    ]);
  });

  it("does not depend on role order", () => {
    expect(resolveCapabilities(["FDS_APPROVER", "FDS_ANALYST"]).granted).toEqual(
      resolveCapabilities(["FDS_ANALYST", "FDS_APPROVER"]).granted,
    );
  });

  it("adds nothing for a role that grants nothing", () => {
    expect(resolveCapabilities(["FDS_VIEWER", "PLATFORM_ADMIN"]).granted).toEqual(
      VIEWER_CAPABILITIES,
    );
  });

  it("grants nothing for a combination of empty roles", () => {
    expect(
      resolveCapabilities(["RULE_OPERATOR", "RECOVERY_OPERATOR", "PLATFORM_ADMIN"]).granted,
    ).toEqual([]);
  });

  it("grants everything reachable when all six roles are held", () => {
    expect(resolveCapabilities([...USER_ROLES]).granted).toEqual([...UI_CAPABILITIES]);
  });
});

describe("resolveCapabilities - fail closed", () => {
  it("grants nothing for a session with no decided roles", () => {
    expect(resolveCapabilities(undefined)).toBe(NO_CAPABILITIES);
  });

  /**
   * Unreachable through the type: `AuthSession.roles` is non-empty and
   * `resolveUserRoles()` never returns an empty tuple. Kept because the
   * fail-closed branch has to hold for a value that arrived from outside the
   * type system, where the failure mode is granting everything.
   */
  it("grants nothing for an empty role set the type cannot produce", () => {
    expect(resolveCapabilities([])).toBe(NO_CAPABILITIES);
  });

  it("grants nothing for a name that is not in the table", () => {
    // Only reachable by defeating the type, which is the point: an
    // unrecognized role must mean "nothing", never "everything".
    expect(resolveCapabilities(["SUPER_ADMIN" as UserRole]).granted).toEqual([]);
  });

  it("ignores an unknown name while honouring a valid one beside it", () => {
    expect(resolveCapabilities(["FDS_VIEWER", "SUPER_ADMIN" as UserRole]).granted).toEqual(
      VIEWER_CAPABILITIES,
    );
  });

  it("holds no capability at all in the shared empty set", () => {
    for (const capability of UI_CAPABILITIES) {
      expect(NO_CAPABILITIES.has(capability)).toBe(false);
    }
    expect(NO_CAPABILITIES.granted).toEqual([]);
  });
});

describe("CapabilitySet", () => {
  it("is frozen", () => {
    const capabilities = resolveCapabilities(["FDS_ANALYST"]);

    expect(Object.isFrozen(capabilities)).toBe(true);
    expect(Object.isFrozen(capabilities.granted)).toBe(true);
  });

  it("cannot be widened through the value it hands out", () => {
    const capabilities = resolveCapabilities(["FDS_VIEWER"]);

    expect(() => {
      (capabilities.granted as UiCapability[]).push("case:resolve");
    }).toThrow(TypeError);
    expect(capabilities.has("case:resolve")).toBe(false);
  });

  it("exposes no mutable collection", () => {
    const capabilities = resolveCapabilities(["FDS_ANALYST"]);

    for (const value of Object.values(capabilities)) {
      expect(value).not.toBeInstanceOf(Set);
      expect(value).not.toBeInstanceOf(Map);
    }
  });

  it("answers only about capabilities it was given", () => {
    const capabilities = resolveCapabilities(["FDS_VIEWER"]);

    expect(capabilities.has("case:view")).toBe(true);
    expect(capabilities.has("case:workflow")).toBe(false);
  });

  it("is not affected by a later call", () => {
    const viewer = resolveCapabilities(["FDS_VIEWER"]);
    resolveCapabilities(["FDS_ANALYST", "FDS_APPROVER"]);

    expect(viewer.granted).toEqual(VIEWER_CAPABILITIES);
  });
});

describe("the capability table as a whole", () => {
  it("declares five capabilities", () => {
    expect(UI_CAPABILITIES).toHaveLength(5);
    expect(Object.isFrozen(UI_CAPABILITIES)).toBe(true);
  });

  it("reaches every declared capability from some role", () => {
    const everything = resolveCapabilities([...USER_ROLES]);

    for (const capability of UI_CAPABILITIES) {
      expect(everything.has(capability)).toBe(true);
    }
  });

  it("declares six USER roles and freezes the list", () => {
    expect(USER_ROLES).toHaveLength(6);
    expect(Object.isFrozen(USER_ROLES)).toBe(true);
  });

  /**
   * Three of the six roles reach nothing today. That is a fact about the
   * screens this client has, not an oversight, and it is asserted so that
   * adding a screen has to update this expectation deliberately.
   */
  it("leaves the operator and admin roles with nothing reachable", () => {
    for (const role of ["RULE_OPERATOR", "RECOVERY_OPERATOR", "PLATFORM_ADMIN"] as const) {
      expect(resolveCapabilities([role]).granted).toEqual([]);
    }
  });
});
