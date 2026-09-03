import { Link } from "react-router-dom";

export function HomePage() {
  return (
    <section aria-labelledby="home-heading">
      <h2 id="home-heading">FinGuardOps Frontend</h2>
      <p>
        This is the initial React and TypeScript foundation for FinGuardOps. Business screens are
        not implemented yet.
      </p>
      <p>
        <Link to="/health">Check backend health</Link>
      </p>
    </section>
  );
}
