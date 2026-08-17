import { redirect } from "next/navigation";
import { getToken } from "@/lib/auth";

/**
 * Server-side gate for every /interview route - redirects to the landing
 * page if there's no session cookie. This is a real check (reads the
 * httpOnly cookie on the server, not a client-side flash-of-content guard),
 * though it only proves a token exists, not that it's still valid - an
 * expired/garbage token still gets past this and fails on the first actual
 * API call instead, which the interview page handles itself (see its
 * ApiError handling).
 */
export default async function InterviewLayout({ children }: LayoutProps<"/interview">) {
  const token = await getToken();
  if (!token) {
    redirect("/");
  }
  return <>{children}</>;
}
