import { redirect } from "next/navigation";
import { getToken } from "@/lib/auth";

/** Same server-side cookie gate as /interview - see that layout's own note. */
export default async function BillingLayout({ children }: LayoutProps<"/billing">) {
  const token = await getToken();
  if (!token) {
    redirect("/");
  }
  return <>{children}</>;
}
