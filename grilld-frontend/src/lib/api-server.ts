import "server-only";
import { getToken } from "@/lib/auth";
import { ApiError, type ApiErrorBody } from "@/lib/types";

const BACKEND_URL = process.env.BACKEND_API_URL ?? "http://localhost:8080";

/**
 * For Server Components only - calls grilld-backend directly, per Next.js's
 * own guidance ("fetch data in Server Components directly from its source,
 * not via Route Handlers"). Client Components must go through /api/proxy
 * instead (src/app/api/proxy/[...path]/route.ts) - see that file's Javadoc-
 * equivalent comment for why the httpOnly cookie makes that unavoidable there.
 */
export async function apiServer<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await getToken();
  const response = await fetch(new URL(`/api/v1${path}`, BACKEND_URL), {
    ...init,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
    cache: "no-store",
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
