import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * Where OAuth2LoginSuccessHandler (grilld-backend) sends the browser after a
 * real Google login, with the freshly issued JWT as ?token=. This is the
 * only place that ever sees the token as a URL parameter - it's immediately
 * moved into an httpOnly cookie and stripped from the address the browser
 * ends up on, so it never sits in browser history or gets read by page JS.
 */
export async function GET(request: NextRequest) {
  const token = request.nextUrl.searchParams.get("token");
  if (!token) {
    return NextResponse.redirect(new URL("/?error=missing_token", request.url));
  }

  const response = NextResponse.redirect(new URL("/interview", request.url));
  response.cookies.set({
    name: SESSION_COOKIE,
    value: token,
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    // Matches TokenService.EXPIRY_HOURS (grilld-backend) - the cookie shouldn't
    // outlive the JWT it holds.
    maxAge: 60 * 60 * 24,
  });
  return response;
}
