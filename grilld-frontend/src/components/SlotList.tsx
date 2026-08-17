import type { SlotView } from "@/lib/types";

const STATUS_MARK: Record<SlotView["status"], string> = {
  FILLED: "✓", // check
  ASSUMED: "≈", // approx
  WAIVED: "–", // dash
  BLOCKED: "⚠", // warning
  OPEN: "",
};

const STATUS_COLOR: Record<SlotView["status"], string> = {
  FILLED: "text-ok",
  ASSUMED: "text-warn",
  WAIVED: "text-ink-soft",
  BLOCKED: "text-danger",
  OPEN: "text-ink-soft/40",
};

/**
 * The live brief panel's actual content - product-and-architecture.md §2.2's
 * "magic-moment UX," rendered as a checklist against the slot graph rather
 * than a chat transcript, since that's what's actually changing turn to turn.
 * Filled slots get a solid line and a value; open ones stay dashed, echoing
 * the "unfilled = dashed outline" drafting convention on the landing page.
 */
export function SlotList({ slots }: { slots: SlotView[] }) {
  const filledCount = slots.filter((s) => s.status === "FILLED" || s.status === "ASSUMED").length;

  return (
    <div className="flex flex-col gap-4">
      <p className="font-mono text-xs text-ink-soft">
        {filledCount} / {slots.length} figured out
      </p>
      <ul className="flex flex-col gap-2.5">
        {slots.map((slot) => (
          <li
            key={slot.slotKey}
            className={`border-b pb-2.5 text-sm leading-snug ${
              slot.status === "OPEN" ? "border-dashed border-ink/15" : "border-ink/15"
            }`}
          >
            <div className="flex items-baseline gap-2">
              <span className={`w-3 shrink-0 font-mono ${STATUS_COLOR[slot.status]}`}>
                {STATUS_MARK[slot.status]}
              </span>
              <span className={slot.status === "OPEN" ? "text-ink-soft" : "text-ink"}>
                {slot.description}
              </span>
            </div>
            {slot.value && <p className="ml-5 mt-1 font-mono text-xs text-blueprint">{slot.value}</p>}
          </li>
        ))}
      </ul>
    </div>
  );
}
