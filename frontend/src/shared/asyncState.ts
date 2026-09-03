export type AsyncState<TData, TError = string> =
  | { status: "loading" }
  | { status: "success"; data: TData }
  | { status: "error"; error: TError };
