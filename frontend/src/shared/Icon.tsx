import type { ReactNode } from "react";

type IconName = "home" | "transactions" | "cases" | "health" | "sign-in" | "sign-out" | "search" | "reset" | "arrow" | "refresh" | "back";

const paths: Record<IconName, ReactNode> = {
  home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z" /><path d="M9 21v-7h6v7" /></>,
  transactions: <><path d="M4 7h16M4 12h16M4 17h16" /><path d="m16 4 4 3-4 3M8 14l-4 3 4 3" /></>,
  cases: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M8 5V3h8v2M3 12h18M10 12v2h4v-2" /></>,
  health: <><path d="M3 12h4l2-5 4 10 2-5h6" /><path d="M4 4h16v16H4z" /></>,
  "sign-in": <><path d="M10 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5M14 8l4 4-4 4M8 12h10" /></>,
  "sign-out": <><path d="M14 4h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5M10 8l-4 4 4 4M16 12H6" /></>,
  search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
  reset: <><path d="M4 11a8 8 0 1 1 2 6M4 5v6h6" /></>,
  arrow: <><path d="M5 12h14m-6-6 6 6-6 6" /></>,
  refresh: <><path d="M20 7v5h-5M4 17v-5h5" /><path d="M5.5 9a7 7 0 0 1 12-2l2.5 5M4 12l2.5 5a7 7 0 0 0 12-2" /></>,
  back: <><path d="M19 12H5m6-6-6 6 6 6" /></>,
};

export function Icon({ name }: { readonly name: IconName }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {paths[name]}
    </svg>
  );
}
