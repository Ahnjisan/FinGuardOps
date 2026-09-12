import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { AuthorizedRequest, AuthSession, CredentialAuthClient } from "../auth/authClient";
import { resolveCapabilities } from "../auth/capabilities";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import {
  changeCaseAssignee,
  changeCaseStatus,
  type CaseAssigneeChangeRequest,
  type CaseDetail,
  type CaseMutation,
  type CaseStatusChangeRequest,
} from "./caseApi";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";

/** The five workflow choices the production section can submit. */
type CaseWorkflowAction =
  | { readonly kind: "start-review"; readonly assigneeRef: string }
  | { readonly kind: "request-additional-information" }
  | { readonly kind: "resume-review" }
  | { readonly kind: "change-assignee"; readonly assigneeRef: string }
  | { readonly kind: "release-assignee" };

export type CaseWorkflowActionKind = CaseWorkflowAction["kind"];

/** Which independent reads a mutation outcome makes authoritative again. */
export type CaseWorkflowReconciliationScope = "detail-audit" | "detail-notes-audit";

interface PublicNotice {
  readonly action: CaseWorkflowActionKind;
}

export type CaseWorkflowMutationState =
  | { readonly status: "idle" }
  | { readonly status: "submitting"; readonly submission: number; readonly notice: PublicNotice }
  | {
      readonly status: "reconciling";
      readonly submission: number;
      readonly result: "success" | "conflict" | "ambiguous";
      readonly notice: PublicNotice;
    }
  | { readonly status: "success"; readonly submission: number; readonly notice: PublicNotice }
  | {
      readonly status: "validation-error";
      readonly submission: number;
      readonly field: "assignee" | "action";
      readonly notice: PublicNotice;
    }
  | { readonly status: "conflict"; readonly submission: number; readonly notice: PublicNotice }
  | { readonly status: "ambiguous"; readonly submission: number; readonly notice: PublicNotice }
  | { readonly status: "forbidden"; readonly submission: number; readonly notice: PublicNotice }
  | { readonly status: "not-found"; readonly submission: number; readonly notice: PublicNotice }
  | {
      readonly status: "authentication-required";
      readonly submission: number;
      readonly notice: PublicNotice;
    }
  | {
      readonly status: "request-rejected";
      readonly submission: number;
      readonly notice: PublicNotice;
    }
  | { readonly status: "server-error"; readonly submission: number; readonly notice: PublicNotice };

interface CaseWorkflowMutationContext {
  readonly caseId: string | null;
  /** The authoritative public projection currently rendered by the page. */
  readonly detail: CaseDetail | null;
  /**
   * Advances only when `useCaseDetail` publishes an authoritative record.
   *
   * submitting 단계에서는 flight identity의 일부라 값이 바뀌면 pending flight가 stale이 되고,
   * reconciling 단계에서는 authoritative detail 도착 신호로서 minimum-version floor와 함께 판정된다.
   */
  readonly reconciliationGeneration: number;
  readonly detailRefreshState: "idle" | "refreshing" | "failed";
  readonly onReconcile: (
    scope: CaseWorkflowReconciliationScope,
    minimumDetailVersion: number,
  ) => void;
}

interface UseCaseWorkflowMutationsResult {
  readonly state: CaseWorkflowMutationState;
  readonly submit: (action: CaseWorkflowAction) => void;
  readonly reset: () => void;
  readonly retryReconciliation: () => void;
  readonly busy: boolean;
}

interface CurrentContext extends CaseWorkflowMutationContext {
  readonly session: AuthSession | null;
  readonly mayMutate: boolean;
}

type FlightPhase = "pending" | "settled" | "released";

interface MutationFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly caseStatus: CaseDetail["caseStatus"];
  readonly assigneeRef: string | null;
  readonly expectedVersion: number;
  readonly action: CaseWorkflowAction;
  readonly submission: number;
  readonly reconciliationGeneration: number;
  readonly detail: CaseDetail;
  readonly controller: AbortController;
  phase: FlightPhase;
}

type StoredTerminalKind =
  | "success"
  | "conflict"
  | "ambiguous"
  | "forbidden"
  | "not-found"
  | "authentication-required"
  | "request-rejected"
  | "server-error";

interface StoredTerminal {
  readonly kind: StoredTerminalKind;
  readonly submission: number;
  readonly notice: PublicNotice;
}

interface ReconciliationBlock {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly generation: number;
  readonly baselineStatus: CaseDetail["caseStatus"];
  readonly baselineAssignee: string | null;
  readonly baselineVersion: number;
  readonly minimumDetailVersion: number;
  readonly scope: CaseWorkflowReconciliationScope;
  readonly outcome: StoredTerminal;
}

function projectDetail(detail: CaseDetail): CaseDetail {
  return {
    caseId: detail.caseId,
    caseStatus: detail.caseStatus,
    finalDisposition: detail.finalDisposition,
    assigneeRef: detail.assigneeRef,
    relatedTransactionCount: detail.relatedTransactionCount,
    createdAt: detail.createdAt,
    reviewStartedAt: detail.reviewStartedAt,
    closedAt: detail.closedAt,
    lastChangedAt: detail.lastChangedAt,
    concurrencyVersion: detail.concurrencyVersion,
  };
}

function projectAction(action: CaseWorkflowAction): CaseWorkflowAction {
  switch (action.kind) {
    case "start-review":
      return { kind: "start-review", assigneeRef: action.assigneeRef };
    case "change-assignee":
      return { kind: "change-assignee", assigneeRef: action.assigneeRef };
    case "request-additional-information":
      return { kind: "request-additional-information" };
    case "resume-review":
      return { kind: "resume-review" };
    case "release-assignee":
      return { kind: "release-assignee" };
  }
}

function storedTerminal(
  kind: StoredTerminalKind,
  flight: MutationFlight,
): StoredTerminal {
  return {
    kind,
    submission: flight.submission,
    notice: { action: flight.action.kind },
  };
}

/** Every React delivery receives a new root and a new nested notice object. */
function deliverTerminal(outcome: StoredTerminal): CaseWorkflowMutationState {
  const notice: PublicNotice = { action: outcome.notice.action };
  switch (outcome.kind) {
    case "success":
      return { status: "success", submission: outcome.submission, notice };
    case "conflict":
      return { status: "conflict", submission: outcome.submission, notice };
    case "ambiguous":
      return { status: "ambiguous", submission: outcome.submission, notice };
    case "forbidden":
      return { status: "forbidden", submission: outcome.submission, notice };
    case "not-found":
      return { status: "not-found", submission: outcome.submission, notice };
    case "authentication-required":
      return { status: "authentication-required", submission: outcome.submission, notice };
    case "request-rejected":
      return { status: "request-rejected", submission: outcome.submission, notice };
    case "server-error":
      return { status: "server-error", submission: outcome.submission, notice };
  }
}

function deliverReconciling(block: ReconciliationBlock): CaseWorkflowMutationState {
  const result = block.outcome.kind;
  if (result !== "success" && result !== "conflict" && result !== "ambiguous") {
    throw new Error("Only an authoritative-read outcome may reconcile.");
  }
  return {
    status: "reconciling",
    submission: block.outcome.submission,
    result,
    notice: { action: block.outcome.notice.action },
  };
}

function classifyFailure(error: unknown, flight: MutationFlight): StoredTerminal {
  if (error instanceof TimeoutError || error instanceof NetworkError || error instanceof InvalidResponseError) {
    return storedTerminal("ambiguous", flight);
  }
  if (error instanceof ForbiddenError) {
    return storedTerminal("forbidden", flight);
  }
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return storedTerminal("authentication-required", flight);
  }
  if (error instanceof HttpError && error.status === 404) {
    return storedTerminal("not-found", flight);
  }
  if (error instanceof HttpError && error.status === 409) {
    return storedTerminal("conflict", flight);
  }
  if (error instanceof RequestNotAllowedError) {
    return storedTerminal("request-rejected", flight);
  }
  return storedTerminal("server-error", flight);
}

function exactOwnKeys(value: object, keys: readonly string[]): boolean {
  const prototype: unknown = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) {
    return false;
  }
  if (Reflect.ownKeys(value).some((key) => typeof key !== "string")) {
    return false;
  }
  const own = Object.getOwnPropertyNames(value).sort();
  return own.length === keys.length && own.every((key, index) => key === [...keys].sort()[index]);
}

/** Rebuilds an action so unknown keys and prototypes never become flight state. */
function readAction(value: unknown): CaseWorkflowAction | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.kind !== "string") {
    return null;
  }
  if (candidate.kind === "start-review" || candidate.kind === "change-assignee") {
    return exactOwnKeys(value, ["assigneeRef", "kind"]) && typeof candidate.assigneeRef === "string"
      ? { kind: candidate.kind, assigneeRef: candidate.assigneeRef }
      : null;
  }
  if (
    candidate.kind === "request-additional-information" ||
    candidate.kind === "resume-review" ||
    candidate.kind === "release-assignee"
  ) {
    return exactOwnKeys(value, ["kind"]) ? { kind: candidate.kind } : null;
  }
  return null;
}

/**
 * submitting 단계 전용 flight identity 판정.
 *
 * submit 시점에 고정한 session·capability·case·status·assignee·version과 `reconciliationGeneration`이
 * 현재 화면 context와 모두 같을 때만 참이다. credential 조회 전, authorize 완료 후, 실제 fetch 직전,
 * 응답 settle 전까지 이 판정 하나를 공유하며, generation만 바뀌어도 flight는 stale이 된다.
 *
 * reconciling 단계에는 쓰지 않는다. 정상 성공 뒤 authoritative detail refresh가 generation을 올리는
 * 것은 stale submit이 아니라 `ReconciliationBlock`의 minimum-version floor 판정 대상이다.
 */
function sameFlightIdentity(current: CurrentContext, flight: MutationFlight): boolean {
  const detail = current.detail;
  return (
    current.session === flight.session &&
    current.mayMutate &&
    current.caseId === flight.caseId &&
    current.reconciliationGeneration === flight.reconciliationGeneration &&
    detail !== null &&
    detail.caseId === flight.caseId &&
    detail.caseStatus === flight.caseStatus &&
    detail.assigneeRef === flight.assigneeRef &&
    detail.concurrencyVersion === flight.expectedVersion
  );
}

function validateAction(
  action: CaseWorkflowAction,
  detail: CaseDetail,
): "assignee" | "action" | null {
  if (!Number.isSafeInteger(detail.concurrencyVersion) || detail.concurrencyVersion < 0 ||
      detail.concurrencyVersion >= Number.MAX_SAFE_INTEGER) {
    return "action";
  }

  switch (action.kind) {
    case "start-review":
      if (detail.caseStatus !== "OPEN") {
        return "action";
      }
      return isCanonicalUuidV4(action.assigneeRef) ? null : "assignee";
    case "request-additional-information":
      return detail.caseStatus === "IN_REVIEW" ? null : "action";
    case "resume-review":
      return detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" && detail.assigneeRef !== null
        ? null
        : "action";
    case "change-assignee":
      if (
        (detail.caseStatus !== "IN_REVIEW" &&
          detail.caseStatus !== "ADDITIONAL_INFORMATION_REQUIRED") ||
        (detail.caseStatus === "IN_REVIEW" && detail.assigneeRef === null)
      ) {
        return "action";
      }
      return !isCanonicalUuidV4(action.assigneeRef) || action.assigneeRef === detail.assigneeRef
        ? "assignee"
        : null;
    case "release-assignee":
      return detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" && detail.assigneeRef !== null
        ? null
        : "action";
  }
}

function statusRequest(action: CaseWorkflowAction, version: number): CaseStatusChangeRequest | null {
  switch (action.kind) {
    case "start-review":
      return {
        targetStatus: "IN_REVIEW",
        assigneeRef: action.assigneeRef,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: version,
      };
    case "request-additional-information":
      return {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: version,
      };
    case "resume-review":
      return {
        targetStatus: "IN_REVIEW",
        reasonCode: "CASE_REVIEW_RESUMED",
        expectedVersion: version,
      };
    case "change-assignee":
    case "release-assignee":
      return null;
  }
}

function assigneeRequest(
  action: CaseWorkflowAction,
  detail: CaseDetail,
): CaseAssigneeChangeRequest | null {
  if (action.kind === "release-assignee") {
    return {
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: detail.concurrencyVersion,
    };
  }
  if (action.kind !== "change-assignee") {
    return null;
  }
  return {
    assigneeRef: action.assigneeRef,
    reasonCode: detail.assigneeRef === null ? "CASE_ASSIGNEE_ASSIGNED" : "CASE_ASSIGNEE_CHANGED",
    expectedVersion: detail.concurrencyVersion,
  };
}

/**
 * 인증 client를 workflow flight 하나에 묶는다. P-2 검사 세 계층 중 앞의 두 계층이다.
 *
 * - 1단계 credential 조회 직전 검사: flight가 현재 session·capability·case·status·assignee·
 *   version·reconciliationGeneration의 소유자가 아니면 인증 port를 호출하지 않는다.
 * - 2단계 authorize 완료 직후 이중 방어 검사: credential 획득을 기다리는 동안 stale이 된 flight의
 *   발급 요청을 transport에 돌려주지 않고 버린다. 버린 요청의 invalidation callback은 호출하지 않으므로
 *   session도 무효화하지 않는다.
 *
 * 2단계는 실제 fetch 직전 검사가 아니다. transport가 prepare를 반환한 뒤 deadline·abort 검사를 거쳐
 * dispatch하기까지 microtask 구간이 남으므로, 최종 검사(3단계)는 transport의 `assertDispatchAllowed`가
 * dispatch와 같은 동기 turn에서 수행한다. 어느 계층의 거부든 고정 `RequestNotAllowedError`다.
 *
 * destination·credential·current-session 판정은 기존 authorized transport와 인증 port가
 * 그대로 수행한다. 이 함수는 그 결과를 바꾸거나 다시 구현하지 않고 전달 여부만 결정한다.
 */
export function bindCredentialLookupToFlight(
  authClient: CredentialAuthClient,
  isFlightCurrent: () => boolean,
): CredentialAuthClient {
  const authorizeRequest = async (request: Request): Promise<AuthorizedRequest | null> => {
    // 1단계: credential 조회 직전 검사.
    if (!isFlightCurrent()) {
      throw new RequestNotAllowedError();
    }
    const authorized = await authClient.authorizeRequest(request);
    // 2단계: authorize 완료 직후 이중 방어 검사. 실제 fetch 직전 최종 검사는 3단계가 맡는다.
    if (!isFlightCurrent()) {
      throw new RequestNotAllowedError();
    }
    return authorized;
  };
  return Object.create(authClient, {
    authorizeRequest: { value: authorizeRequest },
  }) as CredentialAuthClient;
}

/**
 * Owns the one shared Status/Assignee mutation lane for a case detail page.
 * No mutation is retried, replayed or optimistically merged.
 */
export function useCaseWorkflowMutations(
  context: CaseWorkflowMutationContext,
): UseCaseWorkflowMutationsResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;
  const mayMutate =
    session !== null && resolveCapabilities(session.roles).has("case:workflow");

  const currentRef = useRef<CurrentContext>({ ...context, session, mayMutate });
  const mountedRef = useRef(false);
  const flightRef = useRef<MutationFlight | null>(null);
  const blockRef = useRef<ReconciliationBlock | null>(null);
  const submissionRef = useRef(0);
  const identityRef = useRef({ session, caseId: context.caseId });
  const [state, setState] = useState<CaseWorkflowMutationState>({ status: "idle" });

  useLayoutEffect(() => {
    currentRef.current = { ...context, session, mayMutate };
  }, [context, session, mayMutate]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      const flight = flightRef.current;
      if (flight !== null && flight.phase === "pending") {
        flight.phase = "released";
        flight.controller.abort();
      }
      if (flightRef.current === flight) {
        flightRef.current = null;
      }
      blockRef.current = null;
    };
  }, []);

  useEffect(() => {
    const previous = identityRef.current;
    if (previous.session === session && previous.caseId === context.caseId) {
      return;
    }
    identityRef.current = { session, caseId: context.caseId };
    const flight = flightRef.current;
    if (flight !== null && flight.phase === "pending") {
      flight.phase = "released";
      flight.controller.abort();
      flightRef.current = null;
    }
    blockRef.current = null;
    setState({ status: "idle" });
  }, [session, context.caseId]);

  // submitting 단계의 release effect. Status, assignee, version, capability 또는
  // reconciliationGeneration 교체는 pending submit을 release/abort한다. transport가 AbortSignal을
  // 무시해도 phase가 released가 되므로 3단계 dispatch guard와 settle gate가 모두 닫힌다.
  // settled 이후(reconciling 단계)의 flight는 여기서 다루지 않는다.
  useEffect(() => {
    const flight = flightRef.current;
    if (flight === null || flight.phase !== "pending" || sameFlightIdentity(currentRef.current, flight)) {
      return;
    }
    flight.phase = "released";
    flight.controller.abort();
    flightRef.current = null;
    setState({ status: "idle" });
  }, [session, mayMutate, context.caseId, context.detail, context.reconciliationGeneration]);

  // reconciling 단계의 block effect. A reconciliation block is released only by
  // a newer authoritative detail delivery that meets its floor. A lower record
  // leaves the lane blocked. 여기서 generation 증가는 stale submit 신호가 아니라
  // authoritative detail이 도착했다는 신호이며 minimum-version floor와 함께 판정한다.
  useEffect(() => {
    const block = blockRef.current;
    if (block === null) {
      return;
    }
    const detail = context.detail;
    if (
      session !== block.session ||
      context.caseId !== block.caseId ||
      !mayMutate ||
      detail === null ||
      detail.caseId !== block.caseId
    ) {
      blockRef.current = null;
      setState({ status: "idle" });
      return;
    }
    if (
      context.reconciliationGeneration <= block.generation &&
      (detail.caseStatus !== block.baselineStatus ||
        detail.assigneeRef !== block.baselineAssignee ||
        detail.concurrencyVersion !== block.baselineVersion)
    ) {
      blockRef.current = null;
      setState({ status: "idle" });
      return;
    }
    if (
      context.reconciliationGeneration <= block.generation ||
      detail.concurrencyVersion < block.minimumDetailVersion
    ) {
      return;
    }
    blockRef.current = null;
    setState(deliverTerminal(block.outcome));
  }, [
    session,
    mayMutate,
    context.caseId,
    context.detail,
    context.reconciliationGeneration,
  ]);

  const settle = useCallback(
    (flight: MutationFlight, createOutcome: () => StoredTerminal): void => {
      if (flight.phase !== "pending") {
        return;
      }
      // submitting 단계의 settle gate. 응답 settle 시점에도 submit 때의 identity(generation 포함)가
      // 그대로여야 한다. 아니면 stale 응답이므로 publish·reconciliation 시작·결과 반영을 모두 하지 않는다.
      const current = currentRef.current;
      if (!mountedRef.current || flightRef.current !== flight || !sameFlightIdentity(current, flight)) {
        flight.phase = "released";
        return;
      }

      // Deliberately lazy: projection/classification happens only after every
      // release and stale-identity gate above has passed.
      const outcome = createOutcome();
      flight.phase = "settled";
      flightRef.current = null;

      if (outcome.kind === "success" || outcome.kind === "conflict" || outcome.kind === "ambiguous") {
        const scope: CaseWorkflowReconciliationScope =
          outcome.kind === "success" ? "detail-audit" : "detail-notes-audit";
        const minimumDetailVersion =
          outcome.kind === "success"
            ? flight.expectedVersion + 1
            : flight.expectedVersion;
        const block: ReconciliationBlock = {
          session: flight.session,
          caseId: flight.caseId,
          generation: flight.reconciliationGeneration,
          baselineStatus: flight.caseStatus,
          baselineAssignee: flight.assigneeRef,
          baselineVersion: flight.expectedVersion,
          minimumDetailVersion,
          scope,
          outcome,
        };
        blockRef.current = block;
        setState(deliverReconciling(block));
        current.onReconcile(scope, minimumDetailVersion);
        return;
      }
      setState(deliverTerminal(outcome));
    },
    [],
  );

  const submit = useCallback(
    (untrustedAction: CaseWorkflowAction): void => {
      const current = currentRef.current;
      if (
        (flightRef.current !== null && flightRef.current.phase === "pending") ||
        blockRef.current !== null
      ) {
        return;
      }

      const nextSubmission = submissionRef.current + 1;
      submissionRef.current = nextSubmission;
      const action = readAction(untrustedAction);
      const notice: PublicNotice = {
        action: action?.kind ?? "request-additional-information",
      };

      if (action === null) {
        setState({ status: "validation-error", submission: nextSubmission, field: "action", notice });
        return;
      }
      if (current.session === null) {
        setState({ status: "authentication-required", submission: nextSubmission, notice });
        return;
      }
      if (!current.mayMutate) {
        setState({ status: "forbidden", submission: nextSubmission, notice });
        return;
      }
      if (
        current.caseId === null ||
        !isCanonicalUuidV4(current.caseId) ||
        current.detail === null ||
        current.detail.caseId !== current.caseId
      ) {
        setState({ status: "request-rejected", submission: nextSubmission, notice });
        return;
      }

      const invalidField = validateAction(action, current.detail);
      if (invalidField !== null) {
        setState({
          status: "validation-error",
          submission: nextSubmission,
          field: invalidField,
          notice,
        });
        return;
      }

      const detail = projectDetail(current.detail);
      const flight: MutationFlight = {
        session: current.session,
        caseId: current.caseId,
        caseStatus: detail.caseStatus,
        assigneeRef: detail.assigneeRef,
        expectedVersion: detail.concurrencyVersion,
        action: projectAction(action),
        submission: nextSubmission,
        reconciliationGeneration: current.reconciliationGeneration,
        detail,
        controller: new AbortController(),
        phase: "pending",
      };
      flightRef.current = flight;
      setState({
        status: "submitting",
        submission: nextSubmission,
        notice: { action: flight.action.kind },
      });

      // submitting 단계의 소유권 판정이며 settle 게시 gate와 같은 조건이다. 이 조건이 거짓이면
      // 어떤 결과도 게시되지 않으므로 P-2 검사 세 계층도 모두 이 조건 하나로 판정한다.
      //   1단계 credential 조회 직전 검사, 2단계 authorize 완료 직후 이중 방어 검사:
      //         `bindCredentialLookupToFlight`
      //   3단계 실제 dispatch/fetch 직전 최종 검사: 아래 `assertDispatchAllowed`
      const isFlightCurrent = (): boolean =>
        flight.phase === "pending" &&
        mountedRef.current &&
        flightRef.current === flight &&
        sameFlightIdentity(currentRef.current, flight);
      const authClient = bindCredentialLookupToFlight(getOidcAuthClient(), isFlightCurrent);
      // 3단계. transport가 prepare·deadline·abort 검사를 마친 뒤 fetch와 같은 동기 turn에서 호출한다.
      // 2단계를 통과한 뒤에도 prepare 반환부터 dispatch까지 microtask 구간이 남으므로, 그 사이의
      // generation·version·session 교체나 release를 여기서 차단한다. 거부는 고정
      // RequestNotAllowedError이고 settle gate도 같은 조건으로 닫혀 있어 어떤 결과도 게시되지 않는다.
      const requestOptions = {
        assertDispatchAllowed: (): void => {
          if (!isFlightCurrent()) {
            throw new RequestNotAllowedError();
          }
        },
      };
      const status = statusRequest(flight.action, flight.expectedVersion);
      const assignee = assigneeRequest(flight.action, flight.detail);
      const request: Promise<{ readonly data: CaseMutation }> =
        status !== null
          ? changeCaseStatus(
              authClient,
              flight.caseId,
              status,
              flight.detail,
              flight.controller.signal,
              requestOptions,
            )
          : changeCaseAssignee(
              authClient,
              flight.caseId,
              assignee as CaseAssigneeChangeRequest,
              flight.detail,
              flight.controller.signal,
              requestOptions,
            );

      request.then(
        (result) => {
          settle(flight, () => {
            // The semantic API validator proved this exact successor. Reading
            // it here is a positive-control projection, never a stored DTO.
            if (result.data.concurrencyVersion !== flight.expectedVersion + 1) {
              return storedTerminal("ambiguous", flight);
            }
            return storedTerminal("success", flight);
          });
        },
        (error: unknown) => {
          settle(flight, () => classifyFailure(error, flight));
        },
      );
    },
    [settle],
  );

  const reset = useCallback(() => {
    if (state.status === "submitting" || state.status === "reconciling") {
      return;
    }
    setState({ status: "idle" });
  }, [state.status]);

  const retryReconciliation = useCallback(() => {
    const block = blockRef.current;
    const current = currentRef.current;
    if (
      block === null ||
      current.detailRefreshState === "refreshing" ||
      current.session !== block.session ||
      current.caseId !== block.caseId ||
      !current.mayMutate
    ) {
      return;
    }
    setState(deliverReconciling(block));
    current.onReconcile(block.scope, block.minimumDetailVersion);
  }, []);

  return {
    state,
    submit,
    reset,
    retryReconciliation,
    busy: state.status === "submitting" || state.status === "reconciling",
  };
}
