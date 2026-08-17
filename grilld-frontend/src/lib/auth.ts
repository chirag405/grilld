import { cookies } from "next/headers";

/**
 * The httpOnly cookie name the Spring-issued JWT lives in. Set once, by
 * /auth/callback right after a real Google login; never written to by
 * client-side JS (that's the point - see /auth/callback/route.ts).
 */
export const SESSION_COOKIE = "grilld_token";

/** Server Components / Route Handlers only - the cookie is httpOnly, so no client-side equivalent exists on purpose. */
export async function getToken(): Promise<string | undefined> {
  const store = await cookies();
  return store.get(SESSION_COOKIE)?.value;
}

export async function requireToken(): Promise<string> {
  const token = await getToken();
  if (!token) {
    throw new Error("Not authenticated");
  }
  return token;
}
