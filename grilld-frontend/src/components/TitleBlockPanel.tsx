import type { ReactNode } from "react";

/**
 * The recurring signature motif (see the landing page's TitleBlock) - reused
 * here as the frame for the live brief panel. A compartmented header like a
 * real engineering drawing's title block, then arbitrary content below.
 */
export function TitleBlockPanel({
  project,
  scale,
  status,
  children,
}: {
  project: string;
  scale: string;
  status: string;
  children: ReactNode;
}) {
  return (
    <aside className="flex h-full flex-col border border-ink/15 bg-paper">
      <div className="grid grid-cols-2 divide-x divide-ink/15 border-b border-ink/15 font-mono text-[11px] text-ink-soft">
        <HeaderField label="project">{project}</HeaderField>
        <HeaderField label="scale">{scale}</HeaderField>
      </div>
      <div className="border-b border-ink/15 font-mono text-[11px] text-ink-soft">
        <HeaderField label="status">{status}</HeaderField>
      </div>
      <div className="flex-1 overflow-y-auto px-4 py-4">{children}</div>
    </aside>
  );
}

function HeaderField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="px-3 py-2">
      <div className="uppercase tracking-widest text-ink-soft/60">{label}</div>
      <div className="mt-0.5 truncate text-ink">{children}</div>
    </div>
  );
}
