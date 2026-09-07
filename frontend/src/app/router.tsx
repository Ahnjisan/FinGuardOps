import { createBrowserRouter, type RouteObject } from "react-router-dom";
import { AppShell } from "./AppShell";
import { RequireCapability } from "./RequireCapability";
import { AuthCallbackPage } from "../pages/AuthCallbackPage";
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
      { path: "health", element: <HealthPage /> },
      { path: "auth/callback", element: <AuthCallbackPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
