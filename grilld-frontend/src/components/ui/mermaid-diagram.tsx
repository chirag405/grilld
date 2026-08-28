"use client";

import { cn } from "@/lib/utils";
import mermaid from "mermaid";
import { useEffect, useId, useState } from "react";

let initialized = false;

/** Configures mermaid exactly once per page, matching the site's light theme
 * (no dark mode exists anywhere else in this app - see globals.css). */
function ensureInitialized() {
  if (initialized) return;
  mermaid.initialize({
    startOnLoad: false,
    theme: "base",
    themeVariables: {
      primaryColor: "#FEF3C7",
      primaryBorderColor: "#D97706",
      primaryTextColor: "#1C1917",
      lineColor: "#D97706",
      secondaryColor: "#FAFAF9",
      tertiaryColor: "#FFFFFF",
      fontFamily: "inherit",
    },
    securityLevel: "strict",
  });
  initialized = true;
}

/**
 * Renders Mermaid diagram source as an actual diagram - the Diagram Agent
 * only ever produces .mmd source (docs/phases/phase-5/README.md's "known
 * limitations"), and until this component existed nothing in the app showed
 * it as anything but a fenced code block. This covers the "live preview"
 * half of that gap; a rendered SVG/PNG baked into the downloaded package is
 * a separate, heavier server-side step deliberately not taken here (see
 * docs/phases/phase-12/README.md).
 */
export function MermaidDiagram({ chart, className }: { chart: string; className?: string }) {
  const id = useId().replace(/:/g, "-");
  const [svg, setSvg] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    ensureInitialized();
    mermaid
      .render(`mermaid-${id}`, chart)
      .then(({ svg }) => {
        if (cancelled) return;
        setSvg(svg);
        setFailed(false);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [chart, id]);

  if (failed) {
    // Diagram source that doesn't parse (a mid-stream partial chunk, or a
    // genuine agent mistake) - fall back to raw text rather than a blank gap.
    return (
      <pre className={cn("overflow-x-auto rounded-xl border border-line bg-card p-4 text-xs", className)}>
        <code>{chart}</code>
      </pre>
    );
  }

  return (
    <div
      className={cn(
        "not-prose flex w-full justify-center overflow-x-auto rounded-xl border border-line bg-paper p-4",
        className,
      )}
    >
      {svg ? (
        <div dangerouslySetInnerHTML={{ __html: svg }} />
      ) : (
        <div className="h-24 w-full animate-pulse rounded-lg bg-secondary/40" />
      )}
    </div>
  );
}
