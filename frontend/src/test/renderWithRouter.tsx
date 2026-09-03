import { render } from "@testing-library/react";
import {
  createMemoryRouter,
  RouterProvider,
  type RouteObject,
} from "react-router-dom";

export function renderWithRouter(
  routes: RouteObject[],
  options: { initialEntries?: string[] } = {},
): ReturnType<typeof render> {
  const { initialEntries = ["/"] } = options;
  const router = createMemoryRouter(routes, { initialEntries });
  return render(<RouterProvider router={router} />);
}
