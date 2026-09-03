import { Link, Outlet } from "react-router-dom";

export function AppShell() {
  return (
    <div>
      <header>
        <h1>FinGuardOps</h1>
        <nav aria-label="Primary">
          <ul>
            <li>
              <Link to="/">Home</Link>
            </li>
            <li>
              <Link to="/health">Health</Link>
            </li>
          </ul>
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
