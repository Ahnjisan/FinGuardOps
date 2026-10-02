import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <section aria-labelledby="not-found-heading">
      <h2 id="not-found-heading">페이지를 찾을 수 없습니다</h2>
      <p>요청한 페이지가 없습니다.</p>
      <p>
        <Link to="/">홈으로 돌아가기</Link>
      </p>
    </section>
  );
}
