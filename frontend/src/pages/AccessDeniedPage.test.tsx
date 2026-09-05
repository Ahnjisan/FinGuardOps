import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { AccessDeniedPage } from "./AccessDeniedPage";

describe("AccessDeniedPage", () => {
  it("names the refusal in a labelled region", () => {
    render(<AccessDeniedPage />);

    const heading = screen.getByRole("heading", { level: 2, name: "Access denied" });
    expect(heading).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Access denied" })).toBeInTheDocument();
  });

  it("states the refusal in fixed wording", () => {
    render(<AccessDeniedPage />);

    expect(screen.getByText("You do not have permission to view this page.")).toBeInTheDocument();
  });

  /**
   * The page must not become a way to read the session back. Naming the role
   * the user holds, or the one that would have worked, would turn a refusal
   * into a disclosure.
   */
  it("discloses no role, authority, claim or subject", () => {
    const { container } = render(<AccessDeniedPage />);

    const text = container.textContent ?? "";
    for (const secret of [
      "FDS_VIEWER",
      "FDS_ANALYST",
      "FDS_APPROVER",
      "RULE_OPERATOR",
      "RECOVERY_OPERATOR",
      "PLATFORM_ADMIN",
      "principal_type",
      "USER",
      "roles",
      "case:",
      "token",
    ]) {
      expect(text).not.toContain(secret);
    }
  });

  it("offers no control that could be mistaken for a retry", () => {
    render(<AccessDeniedPage />);

    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });

  it("renders nothing that is merely disabled", () => {
    const { container } = render(<AccessDeniedPage />);

    expect(container.querySelectorAll("[disabled], [aria-disabled]")).toHaveLength(0);
  });
});
