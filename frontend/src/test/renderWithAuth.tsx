import { StrictMode } from "react";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider, type RouteObject } from "react-router-dom";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthClient } from "../auth/authClient";

export interface RenderWithAuthOptions {
  readonly initialEntries?: string[];
  readonly client: AuthClient;
  /** StrictMode is on by default: the double-invoke is the interesting case. */
  readonly strict?: boolean;
}

export function renderRoutesWithAuth(
  routes: RouteObject[],
  options: RenderWithAuthOptions,
): ReturnType<typeof render> {
  const { initialEntries = ["/"], client, strict = true } = options;
  const router = createMemoryRouter(routes, { initialEntries });
  const tree = (
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>
  );
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
}
