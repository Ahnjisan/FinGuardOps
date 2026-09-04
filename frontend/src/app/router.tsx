import { createBrowserRouter, type RouteObject } from "react-router-dom";
import { AppShell } from "./AppShell";
import { AuthCallbackPage } from "../pages/AuthCallbackPage";
import { HealthPage } from "../pages/HealthPage";
import { HomePage } from "../pages/HomePage";
import { NotFoundPage } from "../pages/NotFoundPage";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      { path: "health", element: <HealthPage /> },
      { path: "auth/callback", element: <AuthCallbackPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export const router = createBrowserRouter(routes);
