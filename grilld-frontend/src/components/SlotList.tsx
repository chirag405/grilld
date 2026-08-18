import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { SlidingNumber } from "@/components/ui/sliding-number";
import type { SlotView } from "@/lib/types";

const STATUS_VARIANT: Record<SlotView["status"], { label: string; className: string }> = {
  FILLED: { label: "filled", className: "bg-ok/10 text-ok border-ok/20" },
  ASSUMED: { label: "assumed", className: "bg-accent-soft text-accent-ink border-accent/20" },
  WAIVED: { label: "waived", className: "bg-secondary text-ink-soft" },
  BLOCKED: { label: "blocked", className: "bg-danger/10 text-danger border-danger/20" },
  OPEN: { label: "open", className: "text-ink-soft/60" },
};

/**
 * The live brief panel's actual content (product-and-architecture.md §2.2's
 * "magic-moment UX") - a checklist against the slot graph rather than a
 * chat transcript, since that's what's actually changing turn to turn.
 */
export function SlotList({ slots }: { slots: SlotView[] }) {
  const filledCount = slots.filter((s) => s.status === "FILLED" || s.status === "ASSUMED").length;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2 font-mono text-xs text-ink-soft">
        <SlidingNumber value={filledCount} /> / {slots.length} figured out
      </div>
      <Progress value={(filledCount / Math.max(slots.length, 1)) * 100} className="h-1.5" />

      <ul className="flex flex-col gap-3">
        {slots.map((slot) => {
          const variant = STATUS_VARIANT[slot.status];
          return (
            <li key={slot.slotKey} className="flex flex-col gap-1.5">
              <div className="flex items-start justify-between gap-2">
                <span
                  className={
                    slot.status === "OPEN" ? "text-sm text-ink-soft" : "text-sm text-ink"
                  }
                >
                  {slot.description}
                </span>
                <Badge variant="outline" className={variant.className}>
                  {variant.label}
                </Badge>
              </div>
              {slot.value && <p className="font-mono text-xs text-accent-ink">{slot.value}</p>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
