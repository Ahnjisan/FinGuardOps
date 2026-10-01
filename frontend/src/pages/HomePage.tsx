import { Link } from "react-router-dom";

export function HomePage() {
  return (
    <section aria-labelledby="home-heading">
      <h2 id="home-heading">FinGuardOps</h2>
      <p>
        Use the navigation to review transactions and cases available to your role, or check
        backend health. Protected records require a signed-in session.
      </p>
      <p>
        <Link to="/health">Check backend health</Link>
      </p>
    </section>
  );
}
