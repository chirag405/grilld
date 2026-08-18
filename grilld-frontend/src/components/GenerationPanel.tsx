"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiClient, runReportEventsUrl } from "@/lib/api-client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Loader } from "@/components/ui/loader";
import { Markdown } from "@/components/ui/markdown";
import { SlidingNumber } from "@/components/ui/sliding-number";
import { Reasoning, ReasoningContent, ReasoningTrigger } from "@/components/ui/reasoning";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  ApiError,
  FULL_BLUEPRINT_CREDITS,
  type BillingBalance,
  type GenerationRunResult,
  type PackageStatusResponse,
  type RunReportUpdate,
  type ScaleCalibrationResult,
} from "@/lib/types";

/**
 * What happens after the interview concludes: calibrate scale, generate the
 * blueprint, watch it happen, download the package. Watches the Run Report
 * over the real SSE stream (RunReportController's /events, proxied through
 * runReportEventsUrl so the browser's EventSource - which can't set an
 * Authorization header - still authenticates via the httpOnly cookie) -
 * the diff-highlighting live canvas the spec eventually wants isn't built
 * yet, but this is the real live feed, not a polling loop standing in for it.
 */
export function GenerationPanel({ sessionId }: { sessionId: string }) {
  const [tier, setTier] = useState<ScaleCalibrationResult | null>(null);
  const [balance, setBalance] = useState<BillingBalance | null>(null);
  const [run, setRun] = useState<GenerationRunResult | null>(null);
  const [report, setReport] = useState<RunReportUpdate | null>(null);
  const [pkg, setPkg] = useState<PackageStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [needsCredits, setNeedsCredits] = useState(false);

  useEffect(() => {
    apiClient<BillingBalance>("/billing/balance").then(setBalance).catch(() => {});
  }, []);

  async function pollPackage(runId: string) {
    const status = await apiClient<PackageStatusResponse>(`/sessions/${sessionId}/runs/${runId}/package`).catch(
      () => null,
    );
    if (!status) return;
    setPkg(status);
    if (status.status === "PENDING") {
      setTimeout(() => pollPackage(runId), 1500);
    }
  }

  useEffect(() => {
    if (!run) return;

    let stopped = false;
    let pollTimer: ReturnType<typeof setTimeout> | undefined;

    const applyUpdate = (update: RunReportUpdate) => {
      if (stopped) return;
      setReport(update);
      if (update.status === "COMPLETED") pollPackage(run.runId);
    };

    const poll = async () => {
      const update = await apiClient<RunReportUpdate>(`/sessions/${sessionId}/runs/${run.runId}/report`).catch(() => null);
      if (update) applyUpdate(update);
      if (!stopped && update?.status === "IN_PROGRESS") pollTimer = setTimeout(poll, 1500);
    };
    void poll();

    const source = new EventSource(runReportEventsUrl(sessionId, run.runId));
    source.addEventListener("report", (event) => {
      const update = JSON.parse((event as MessageEvent).data) as RunReportUpdate;
      applyUpdate(update);
    });
    source.onerror = () => {
      source.close();
      if (!pollTimer) pollTimer = setTimeout(poll, 500);
    };

    return () => {
      stopped = true;
      source.close();
      if (pollTimer) clearTimeout(pollTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run]);

  async function calibrate() {
    setBusy(true);
    setError(null);
    try {
      const result = await apiClient<ScaleCalibrationResult>(`/sessions/${sessionId}/scale-tier`, {
        method: "POST",
      });
      setTier(result);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't calibrate scale. Try again.");
    } finally {
      setBusy(false);
    }
  }

  async function generate() {
    if (balance && balance.creditsBalance < FULL_BLUEPRINT_CREDITS) {
      setNeedsCredits(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await apiClient<GenerationRunResult>(`/sessions/${sessionId}/generate`, {
        method: "POST",
      });
      setRun(result);
    } catch (e) {
      if (e instanceof ApiError && e.status === 402) {
        setNeedsCredits(true);
      } else {
        setError(e instanceof ApiError ? e.message : "Couldn't start generation. Try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="max-w-2xl gap-5">
      <CardHeader className="flex-row items-center justify-between px-5">
        <Badge variant="secondary">interview complete</Badge>
        {balance && (
          <span className="flex items-center gap-1 font-mono text-xs text-ink-soft">
            <SlidingNumber value={balance.creditsBalance} /> credits
          </span>
        )}
      </CardHeader>

      <CardContent className="flex flex-col gap-5 px-5">
        {!tier ? (
          <Button onClick={calibrate} disabled={busy} variant="outline" className="self-start">
            {busy ? "Calibrating…" : "Calibrate scale"}
          </Button>
        ) : (
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium text-ink">Scale: {tier.tier}</p>
            <p className="text-sm text-ink-soft">{tier.reasoning}</p>
          </div>
        )}

        {tier && !run && (
          <Button onClick={generate} disabled={busy} className="self-start">
            {busy ? "Starting…" : `Generate blueprint (${FULL_BLUEPRINT_CREDITS} credits)`}
          </Button>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {report && (
          <div className="flex flex-col gap-3 rounded-lg border border-line bg-secondary/40 p-4">
            <div className="flex items-center justify-between">
              <p className="font-mono text-xs uppercase tracking-widest text-ink-soft">Run Report</p>
              <Badge variant={report.status === "FAILED" ? "destructive" : "outline"}>
                {report.status}
              </Badge>
            </div>
            <div className="flex items-center gap-3">
              <Progress value={(report.completedAgents / Math.max(report.totalAgents, 1)) * 100} className="h-1.5 flex-1" />
              <span className="font-mono text-xs text-ink-soft">{report.completedAgents}/{report.totalAgents}</span>
            </div>
            <p className="text-sm font-medium text-ink">Now: {report.currentStep.replaceAll("_", " ")}</p>
            <Reasoning open={report.status === "IN_PROGRESS"} isStreaming={report.status === "IN_PROGRESS"}>
              <ReasoningTrigger className="text-xs text-ink-soft">Document creation steps</ReasoningTrigger>
              <ReasoningContent contentClassName="mt-3 space-y-2">
                {report.steps.map((step, index) => (
                  <div key={step.agentName} className="flex gap-3 text-xs">
                    <span className="w-5 font-mono text-ink-soft">{index + 1}</span>
                    <div className="flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium text-ink">{step.agentName.replaceAll("_", " ")}</span>
                        <span className="font-mono text-[10px] text-ink-soft">{step.status.toLowerCase()}</span>
                      </div>
                      {step.narration && <p className="text-ink-soft">{step.narration}</p>}
                      {step.documents.length > 0 && <p className="font-mono text-[10px] text-accent-ink">{step.documents.join(", ")}</p>}
                    </div>
                  </div>
                ))}
              </ReasoningContent>
            </Reasoning>
            {report.runReportMd ? (
              <Markdown className="prose prose-sm max-h-64 max-w-none overflow-y-auto text-ink prose-headings:text-ink prose-strong:text-ink">
                {report.runReportMd}
              </Markdown>
            ) : (
              <Loader variant="text-shimmer" text="Assembling…" size="sm" />
            )}
            {report.status === "FAILED" && report.failureReason && (
              <Alert variant="destructive">
                <AlertDescription>{report.failureReason}</AlertDescription>
              </Alert>
            )}
          </div>
        )}

        {pkg?.status === "READY" && run && (
          <Button asChild className="self-start">
            <a href={`/api/proxy/sessions/${sessionId}/runs/${run.runId}/package/download`}>
              Download blueprint (.zip)
            </a>
          </Button>
        )}
      </CardContent>

      <Dialog open={needsCredits} onOpenChange={setNeedsCredits}>
        <DialogContent
          showCloseButton={false}
          onEscapeKeyDown={(e) => e.preventDefault()}
          onPointerDownOutside={(e) => e.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>You&rsquo;re out of credits</DialogTitle>
            <DialogDescription>
              A full blueprint run costs {FULL_BLUEPRINT_CREDITS} credits - you have{" "}
              {balance?.creditsBalance ?? 0}. Buy a package to keep going; your interview and brief
              are saved and waiting.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button asChild className="w-full">
              <Link href="/billing">Go to billing</Link>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
