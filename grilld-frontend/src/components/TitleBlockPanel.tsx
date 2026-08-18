import type { ReactNode } from "react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

/**
 * The live brief panel's frame - project idea, current scale tier, and
 * interview status, with the slot checklist (children) below.
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
    <Card className="h-full gap-4 rounded-none border-0 border-l py-5 shadow-none">
      <CardHeader className="gap-3 px-5">
        <div>
          <p className="font-mono text-[11px] uppercase tracking-widest text-ink-soft">project</p>
          <p className="mt-0.5 truncate text-sm font-medium text-ink">{project}</p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline" className="font-mono text-[11px]">
            scale {scale}
          </Badge>
          <Badge variant="secondary" className="font-mono text-[11px]">
            {status}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="flex-1 overflow-y-auto px-5">{children}</CardContent>
    </Card>
  );
}
