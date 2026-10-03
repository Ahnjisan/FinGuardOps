import { Link } from "react-router-dom";
import { useCapabilities } from "../auth/useCapabilities";
import { useAuth } from "../auth/useAuth";
import { Icon } from "../shared/Icon";
import { CaseWorkOverview } from "./home/CaseWorkOverview";

export function HomePage() {
  const capabilities = useCapabilities();
  const { state } = useAuth();
  const canReadCases = state.status === "authenticated" && capabilities.has("case:view");

  return (
    <section className="home" aria-labelledby="home-heading">
      <div className="home__hero">
        <p className="home__eyebrow">이상거래 대응 업무 공간</p>
        <h2 id="home-heading">FinGuardOps</h2>
        <p className="home__lede">
          현재 사건을 확인하고 조사할 사건으로 이동하는 업무 공간입니다.
        </p>
        <div className="home__hero-actions">
          {canReadCases && (
            <Link className="button button--hero" to="/cases"><Icon name="cases" />사건 대기열 열기</Link>
          )}
          <Link className="home__hero-link" to="/health"><Icon name="health" />서비스 상태 확인</Link>
        </div>
      </div>

      {canReadCases && <CaseWorkOverview />}

      <div className="home__section-head">
        <p className="home__section-label">업무 공간</p>
        <h3>업무를 시작하세요</h3>
        <p>권한에 따라 이용할 수 있는 화면만 표시됩니다.</p>
      </div>
      <div className="home__cards">
        {capabilities.has("case:view") && (
          <Link className="home__card" to="/cases">
            <span className="home__card-kicker">조사 업무</span>
            <span className="home__card-title">사건 <Icon name="arrow" /></span>
            <span className="home__card-copy">사건을 찾고 현재 상태를 확인합니다.</span>
          </Link>
        )}
        {capabilities.has("transaction:view") && (
          <Link className="home__card" to="/transactions">
            <span className="home__card-kicker">거래 조회</span>
            <span className="home__card-title">거래 <Icon name="arrow" /></span>
            <span className="home__card-copy">거래 기록을 조회합니다.</span>
          </Link>
        )}
        <Link className="home__card" to="/health">
          <span className="home__card-kicker">서비스</span>
          <span className="home__card-title">서비스 상태 <Icon name="arrow" /></span>
          <span className="home__card-copy">백엔드의 현재 상태를 확인합니다.</span>
        </Link>
      </div>
    </section>
  );
}
