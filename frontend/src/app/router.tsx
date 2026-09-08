import { createBrowserRouter, type RouteObject } from "react-router-dom";
import { AppShell } from "./AppShell";
import { RequireCapability } from "./RequireCapability";
import { AuthCallbackPage } from "../pages/AuthCallbackPage";
import { CaseDetailPage } from "../pages/CaseDetailPage";
import { CaseListPage } from "../pages/CaseListPage";
import { HealthPage } from "../pages/HealthPage";
import { HomePage } from "../pages/HomePage";
import { NotFoundPage } from "../pages/NotFoundPage";
import { TransactionDetailPage } from "../pages/TransactionDetailPage";
import { TransactionListPage } from "../pages/TransactionListPage";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      {
        // The first production route behind a capability. The guard runs for a
        // direct URL entry exactly as it does for a click on the rail, because
        // it is part of the element rather than part of the navigation. It is a
        // convenience boundary only: Backend re-decides authorization from the
        // access token on every request and answers 401 or 403.
        path: "transactions",
        element: (
          <RequireCapability capability="transaction:view">
            <TransactionListPage />
          </RequireCapability>
        ),
      },
      {
        // The detail screen, behind the same capability as the list. The
        // parameter is a route *slot*, not a contract: `TransactionDetailPage`
        // decides from the location the browser's URL parser handed over - its
        // pathname, search and hash - whether this address names a canonical
        // transaction at all, and refuses it before any credential is looked up
        // when it does not. That parsed location is the application's input
        // boundary; a representation the browser resolved away before any of
        // this ran is neither recovered nor told apart here. Anything that is
        // not one segment under `/transactions/` never reaches here - it falls
        // through to the catch-all below.
        path: "transactions/:transactionId",
        element: (
          <RequireCapability capability="transaction:view">
            <TransactionDetailPage />
          </RequireCapability>
        ),
      },
      {
        // The case list, behind its own capability. `case:view`, not
        // `transaction:view`: the two are separate UI capabilities backed by
        // separate Backend authorities, and a role that may read the ledger is
        // not thereby a role that may read investigations.
        //
        // Exact `/cases` and no more. The detail route below is a separate
        // route with its own path, not a widening of this one, so `/casesx` and
        // every deeper path under `/cases/{caseId}` still fall through to the
        // catch-all. `/cases/` is the exception React Router itself decides: it
        // matches this exact route before any of this application's code runs,
        // so a trailing slash renders the list rather than reaching the detail
        // screen with an empty identifier. The guard sits on the element, so a direct
        // URL entry is decided exactly as a click on the rail is. It remains a
        // convenience boundary: Backend re-decides authorization from the
        // access token on every request.
        path: "cases",
        element: (
          <RequireCapability capability="case:view">
            <CaseListPage />
          </RequireCapability>
        ),
      },
      {
        // The case detail screen, behind the same capability as the case list -
        // `case:view`, backed by Backend authority `case:read`. The parameter
        // is a route *slot*, not a contract: `CaseDetailPage` decides from the
        // location the browser's URL parser handed over - its pathname, search
        // and hash - whether this address names a canonical case at all, and
        // refuses it before any credential is looked up when it does not. That
        // parsed location is the application's input boundary; a representation
        // the browser resolved away before any of this ran is neither recovered
        // nor told apart here. Anything that is not one segment under `/cases/`
        // never reaches here - it falls through to the catch-all below.
        path: "cases/:caseId",
        element: (
          <RequireCapability capability="case:view">
            <CaseDetailPage />
          </RequireCapability>
        ),
      },
      { path: "health", element: <HealthPage /> },
      { path: "auth/callback", element: <AuthCallbackPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
