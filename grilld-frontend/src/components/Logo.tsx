import { Flame } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The one mark used everywhere the brand needs to show up - browser tab
 * (src/app/icon.svg is the same flame-on-amber-square, kept in sync by hand
 * since a static favicon file can't import this component), page headers,
 * and anywhere else "grilld" would otherwise just be plain text.
 */
export function Logo({
  withWordmark = true,
  size = "default",
  className,
}: {
  withWordmark?: boolean;
  size?: "sm" | "default";
  className?: string;
}) {
  const badge = size === "sm" ? "h-5 w-5 rounded-[5px]" : "h-6 w-6 rounded-[6px]";
  const icon = size === "sm" ? "h-3 w-3" : "h-3.5 w-3.5";
  const text = size === "sm" ? "text-sm" : "text-base";

  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <span className={cn("flex shrink-0 items-center justify-center bg-[#D97706]", badge)}>
        <Flame className={cn("text-[#FAFAFA]", icon)} strokeWidth={2.5} />
      </span>
      {withWordmark && <span className={cn("font-semibold tracking-tight text-ink", text)}>grilld</span>}
    </span>
  );
}
