import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <section aria-labelledby="not-found-heading">
      <h2 id="not-found-heading">Page not found</h2>
      <p>The page you requested does not exist.</p>
      <p>
        <Link to="/">Return home</Link>
      </p>
    </section>
  );
}
