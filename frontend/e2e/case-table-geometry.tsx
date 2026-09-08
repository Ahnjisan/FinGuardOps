/**
 * TEST-ONLY BROWSER GEOMETRY FIXTURE. NOT PART OF THE APPLICATION.
 *
 * The production `CaseTable`, rendered by the production React, styled by the
 * production `app.css`, with rows in it - so a Chromium test can measure what
 * a populated case sheet actually does at the three console design widths.
 *
 * Why it exists. The E2E runtime holds no seeded fraud cases, so the real
 * Backend answers the case list with a genuine, correct, empty page. That is
 * honest evidence about the endpoint and useless evidence about layout: an
 * empty `tbody` cannot overflow anything. The alternative - intercepting the
 * API and fulfilling it with an invented body - would turn a real-Backend test
 * into a test of the fixture, so it is not taken. Instead the component is
 * mounted directly, with no transport underneath it at all.
 *
 * What this file therefore is *not*:
 *
 * - not evidence of Backend integration, and never to be reported as such. The
 *   real case-list contract is proved by the real-Backend test, which sends the
 *   exact query, reaches Spring Boot and reads its real 200.
 * - not an API mock and not a route interception. Nothing here fetches, so
 *   there is nothing to intercept.
 * - not an authentication bypass. There is no session, no token and no guarded
 *   route in this page; `RequireCapability` is a router concern and this file
 *   mounts no router.
 * - not reachable from the application. Nothing imports it, the production
 *   router has no test route, and `vite build` builds `index.html` alone.
 *
 * The rows below are fixed, synthetic and structural. They carry no credential,
 * no token, no customer, no account and no amount - the case list contract has
 * no amount field at all - and every value is chosen to make a column render at
 * its widest: a canonical UUID identifier, an assignee reference at Backend's
 * 128-character maximum, and the two nullable fields in both of their states.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseListItem } from "../src/api/caseApi";
import { CaseTable } from "../src/pages/cases/CaseTable";
import "../src/styles/app.css";

/**
 * An assignee reference at Backend's bound, exactly 128 characters.
 *
 * The widest value this column can ever hold, so it is the one that decides
 * whether a long reference wraps inside its cell or pushes the document
 * sideways. Four repetitions of one obviously synthetic token: it names the
 * fixture rather than resembling a real operator key.
 */
const LONG_ASSIGNEE_REF =
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-";

/**
 * Five rows covering every branch the sheet can take.
 *
 * All four case statuses, all three final dispositions - `NORMAL`,
 * `FALSE_POSITIVE` and `CONFIRMED_FRAUD` - plus the unresolved `null`, an
 * assignee at the maximum length, an absent assignee, and counts of zero and of
 * two digits. Every disposition is rendered rather than described, so the label
 * a resolved-normal case is shown under is measured in a browser instead of
 * being taken on trust from the label map. Typed as `CaseListItem`, so a change
 * to the Backend contract stops this fixture compiling rather than letting it
 * drift into measuring a table the application no longer renders.
 *
 * The identifiers are synthetic and carry no customer, account or credential:
 * each is a fixed UUID written for this fixture alone.
 */
const GEOMETRY_CASES: readonly CaseListItem[] = [
  {
    caseId: "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d",
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: LONG_ASSIGNEE_REF,
    relatedTransactionCount: 12,
    createdAt: "2026-01-02T03:04:05Z",
    lastChangedAt: "2026-03-04T05:06:07Z",
  },
  {
    caseId: "1b2c3d4e-5f6a-4b7c-9d0e-1f2a3b4c5d6e",
    caseStatus: "CLOSED",
    finalDisposition: "CONFIRMED_FRAUD",
    assigneeRef: null,
    relatedTransactionCount: 0,
    createdAt: "2026-01-03T04:05:06Z",
    lastChangedAt: "2026-03-05T06:07:08Z",
  },
  {
    caseId: "2c3d4e5f-6a7b-4c8d-a9e0-2a3b4c5d6e7f",
    caseStatus: "OPEN",
    finalDisposition: null,
    assigneeRef: "e2e-geometry-analyst-01",
    relatedTransactionCount: 3,
    createdAt: "2026-01-04T05:06:07Z",
    lastChangedAt: "2026-03-06T07:08:09Z",
  },
  {
    caseId: "3d4e5f6a-7b8c-4d9e-b0f1-3b4c5d6e7f80",
    caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
    finalDisposition: "FALSE_POSITIVE",
    assigneeRef: "e2e-geometry-analyst-02",
    relatedTransactionCount: 7,
    createdAt: "2026-01-05T06:07:08Z",
    lastChangedAt: "2026-03-07T08:09:10Z",
  },
  {
    // The third disposition. Without it the sheet was measured with two of the
    // three verdicts on screen, so nothing said what a case an investigator
    // concluded was normal actually renders as. A closed case, because that is
    // the state a concluded investigation is in.
    caseId: "4e5f6a7b-8c9d-4e0f-9a1b-4c5d6e7f8091",
    caseStatus: "CLOSED",
    finalDisposition: "NORMAL",
    assigneeRef: "e2e-geometry-analyst-03",
    relatedTransactionCount: 1,
    createdAt: "2026-01-06T07:08:09Z",
    lastChangedAt: "2026-03-08T09:10:11Z",
  },
];

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
      to do with table geometry. Everything from `.cases` down is the markup
      `CaseListPage` itself renders; the table is the production component,
      never a copy of it.
    */}
    <div className="app">
      <div className="rail" />
      <main className="main" id="main-content">
        <section className="cases" aria-labelledby="cases-heading">
          <div className="page-head">
            <h2 id="cases-heading">Case table geometry fixture (test only)</h2>
            <p>
              Browser geometry measurement of the production case table. No request is
              made and no Backend is involved.
            </p>
          </div>
          <CaseTable
            items={GEOMETRY_CASES}
            sort="lastChangedAt,desc"
            onSortChange={() => undefined}
          />
        </section>
      </main>
    </div>
  </StrictMode>,
);
