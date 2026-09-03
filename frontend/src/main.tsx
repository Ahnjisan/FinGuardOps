import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import { getEnv } from "./config/env";
import { router } from "./app/router";

export function bootstrap(): void {
  // Fail fast: validate configuration once, before touching the DOM or
  // rendering anything, so an invalid environment never reaches the UI.
  getEnv();

  const rootElement = document.getElementById("root");
  if (!rootElement) {
    throw new Error("Root element not found.");
  }

  createRoot(rootElement).render(
    <StrictMode>
      <RouterProvider router={router} />
    </StrictMode>,
  );
}

bootstrap();
