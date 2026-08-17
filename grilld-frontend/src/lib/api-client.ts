"use client";

import { ApiError, type ApiErrorBody } from "@/lib/types";

/**
 * For Client Components - always goes through /api/proxy (never grilld-backend
 * directly), since the JWT lives in an httpOnly cookie the browser can't read
 * to build an Authorization header itself. See src/app/api/proxy/[...path]/route.ts.
 */
export async function apiClient<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/proxy${path}`, {
    ...init,
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw new ApiError(response.status, body?.message ?? `Request to ${path} failed (${response.status})`);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

/** The Run Report's live SSE endpoint, proxied the same way - see the proxy route's streaming note. */
export function runReportEventsUrl(sessionId: string, runId: string): string {
  return `/api/proxy/sessions/${sessionId}/runs/${runId}/events`;
}
