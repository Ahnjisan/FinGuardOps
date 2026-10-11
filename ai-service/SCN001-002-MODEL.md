# 합성 SCN-001·002 모델 경계

`fraud-feature-v2`는 Backend가 거래 T에 고정한 `scenarioFacts`의 기준선 상태/비율, 최초 수취인, 신규·검증 기기, 비밀번호·한도 순서와 상향 폭, 인증 관측 상태, 정확 Provider 기기 match, 앱/지역 Mock만 사용한다. 원문 고객·계좌·기기·IP, 사후 인증 결과, 사건 상태/판정, T 이후 발생·저장 자료는 Feature로 사용하지 않는다. 누락은 `UNKNOWN`으로 인코딩한다.
ATM·대출은 금액·채널 기본 Feature만 사용하고 SCN-001/002 신호 Feature 10개는 0으로 둔다. 동일 모델 버전을 쓰지만 해당 거래 유형에서 이체 시나리오 근거를 만들지 않는다.

`python -m finguardops_ai.ml.train_v3`은 seed 384의 시간·고객 분리 합성 데이터 600/600/600건으로 `fraud-logistic-v3`을 결정적으로 생성한다. 핀된 아티팩트 SHA-256은 `630a7d078ab35f8b9b49e8d1aadfaaf583af64452edd68fa2542d13b5b4b4636`이다. `fraud_model_v3.evaluation.json`의 Precision·Recall 등은 이 합성 데이터에만 해당하며 실제 금융 사기 성능이나 운영 임계값이 아니다. 배포 시 SHA/Feature/모델 버전 불일치는 fail-closed 한다.
