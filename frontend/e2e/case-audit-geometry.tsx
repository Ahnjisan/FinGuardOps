/**
 * TEST-ONLY BROWSER GEOMETRY FIXTURE. NOT PART OF THE APPLICATION.
 *
 * The production `CaseAuditPanel`, rendered by the production React, styled by
 * the production `app.css`, with entries in it - so a Chromium test can measure
 * what a populated audit history actually does at the three console design
 * widths.
 *
 * Why it exists. The E2E runtime holds no seeded fraud cases, so the real
 * Backend answers the audit endpoint for the synthetic identifier with a
 * genuine 404. That is honest evidence about the endpoint and useless evidence
 * about layout: a panel that was never rendered cannot overflow anything. The
 * alternative - intercepting the API and fulfilling it with an invented body -
 * would turn a real-Backend test into a test of the fixture, so it is not
 * taken. Instead the component is mounted directly, with no transport
 * underneath it at all.
 *
 * `CaseAuditPanel` is the production component, not a copy of it. The connected
 * `CaseAuditSection` is the hook plus this panel, and the hook is the only part
 * that needs a session and a transport; the panel takes a settled state and
 * three callbacks and reaches nothing else. Mounting the connected component
 * here would render its `idle` state - an empty panel - which is precisely the
 * thing that cannot be measured.
 *
 * What this file therefore is *not*:
 *
 * - not evidence of Backend integration, and never to be reported as such. The
 *   real audit request is proved by the real-Backend test, which sends the
 *   exact target, reaches Spring Boot and reads its real 404.
 * - not an API mock and not a route interception. Nothing here fetches, so
 *   there is nothing to intercept.
 * - not an authentication bypass. There is no session, no token and no guarded
 *   route in this page; `RequireCapability` is a router concern and this file
 *   mounts no router.
 * - not reachable from the application. Nothing imports it, the production
 *   router has no test route, and `vite build` builds `index.html` alone.
 *
 * The entries below are fixed, synthetic and structural. They carry no
 * credential, no token, no customer and no account, and between them they cover
 * every branch the panel can take: all six `AuditAction` values, all four
 * summary shapes, a `null` summary on both sides, a `null` assignee, the
 * longest reason code the contract has, and the note identifier only a
 * `CASE_NOTE_CREATED` entry carries.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseAuditEntry } from "../src/api/caseAuditApi";
import type { CaseAuditView } from "../src/api/useCaseAuditLog";
import { CaseAuditPanel } from "../src/pages/cases/CaseAuditSection";
import "../src/styles/app.css";

/**
 * A reference at the console's 128-character bound.
 *
 * Longer than any assignee reference an audit summary can actually carry -
 * `isCaseAuditPage` admits only a canonical UUID v4 there - and that is the
 * point. `.audit__summary-value` is the same wrapping rule the case detail
 * screen spends on `assigneeRef`, where 128 characters *is* the contract bound,
 * so this measures the rule at the widest value the console has to survive
 * rather than at the widest value this one endpoint can produce. It is a width
 * probe, and it is not evidence about the audit contract.
 */
const LONG_REFERENCE =
  "e2e-geometry-audit-reference-000" +
  "e2e-geometry-audit-reference-111" +
  "e2e-geometry-audit-reference-222" +
  "e2e-geometry-audit-reference-333";

/** A canonical assignee reference, as an audit summary really carries one. */
const ASSIGNEE_REF = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";

/** The note identifier the `CASE_NOTE_CREATED` entry carries. */
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";

/**
 * Six entries: one per `AuditAction`, and between them every summary shape.
 *
 * Typed as `CaseAuditEntry`, so a change to the Backend contract stops this
 * fixture compiling rather than letting it drift into measuring a panel the
 * application no longer renders.
 */
const GEOMETRY_ENTRIES: readonly CaseAuditEntry[] = [
  {
    // `CaseStatusSummary`, and a `null` before-state.
    action: "CASE_CREATED",
    reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
    actorType: "SYSTEM",
    changedAt: "2026-03-08T09:10:11.123456Z",
    beforeSummary: null,
    afterSummary: { caseStatus: "OPEN" },
    metadata: {},
  },
  {
    // `LinkedSummary`.
    action: "CASE_TRANSACTION_LINKED",
    reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
    actorType: "SYSTEM",
    changedAt: "2026-03-08T09:12:00.000001Z",
    beforeSummary: null,
    afterSummary: { linked: true },
    metadata: {},
  },
  {
    // `WorkflowSummary` on both sides, the longest reason code in the contract,
    // the longest case status in it, and the 128-character width probe.
    action: "CASE_STATUS_CHANGED",
    reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
    actorType: "USER",
    changedAt: "2026-03-09T01:02:03.456789Z",
    beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: LONG_REFERENCE },
    afterSummary: {
      caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      assigneeRef: LONG_REFERENCE,
    },
    metadata: {},
  },
  {
    // A released assignee: the `null` an audit summary field can carry, as
    // distinct from a summary that is `null` as a whole.
    action: "CASE_ASSIGNEE_CHANGED",
    reasonCode: "CASE_ASSIGNEE_RELEASED",
    actorType: "USER",
    changedAt: "2026-03-09T02:03:04.000010Z",
    beforeSummary: {
      caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      assigneeRef: ASSIGNEE_REF,
    },
    afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
    metadata: {},
  },
  {
    // `ResolutionSummary`, the only shape with three fields.
    action: "CASE_RESOLVED",
    reasonCode: "CASE_RESOLUTION_COMPLETED",
    actorType: "USER",
    changedAt: "2026-03-10T04:05:06.999999Z",
    beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_REF },
    afterSummary: {
      caseStatus: "CLOSED",
      assigneeRef: ASSIGNEE_REF,
      finalDisposition: "CONFIRMED_FRAUD",
    },
    metadata: {},
  },
  {
    // Both summaries `null`, and the one action that carries metadata.
    action: "CASE_NOTE_CREATED",
    reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
    actorType: "USER",
    changedAt: "2026-03-10T05:06:07.000000Z",
    beforeSummary: null,
    afterSummary: null,
    metadata: { noteId: NOTE_ID },
  },
];

/**
 * The page envelope the entries sit in.
 *
 * Internally consistent in the same way `isConsistentPageMetadata` requires,
 * even though no validator runs here: a fixture whose metadata contradicted
 * itself would be measuring a page the application could never display.
 */
const GEOMETRY_VIEW: CaseAuditView = {
  content: GEOMETRY_ENTRIES,
  page: {
    number: 0,
    size: 20,
    totalElements: GEOMETRY_ENTRIES.length,
    totalPages: 1,
    first: true,
    last: true,
  },
};

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element not found.");
}

createRoot(rootElement).render(
  <StrictMode>
    {/*
      The console's own layout classes, and only the ones the measurement
      depends on. `.app` is the grid whose first track is `--rail-width`, so the
      empty `.rail` below is a spacer that gives `.main` the width it really has
      at 1440, 1280 and 1024 - not a copy of the navigation, which has nothing
      to do with panel geometry. `.detail` is the case detail screen's own
      wrapper, which is where the section really sits.
    */}
    <div className="app">
      <div className="rail" />
      <main className="main" id="main-content">
        <section className="detail" aria-labelledby="case-audit-fixture-heading">
          <div className="page-head">
            <h2 id="case-audit-fixture-heading">Case audit geometry fixture (test only)</h2>
            <p>
              Browser geometry measurement of the production audit history panel. No request
              is made and no Backend is involved.
            </p>
          </div>
          {/*
            The production component. The callbacks lead nowhere on purpose:
            this page measures a settled page of entries, and paging it would
            need the hook, the transport and a session that this fixture
            deliberately does not have.
          */}
          <CaseAuditPanel
            state={{ status: "success", data: GEOMETRY_VIEW }}
            onPageChange={() => undefined}
            onPageSizeChange={() => undefined}
            onRetry={() => undefined}
          />
        </section>
      </main>
    </div>
  </StrictMode>,
);
