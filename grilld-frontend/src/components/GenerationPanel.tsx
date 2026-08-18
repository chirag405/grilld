"use client";

import { useEffect, useState } from "react";
import { apiClient, runReportEventsUrl } from "@/lib/api-client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Loader } from "@/components/ui/loader";
import { Markdown } from "@/components/ui/markdown";
import { SlidingNumber } from "@/components/ui/sliding-number";
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

    const source = new EventSource(runReportEventsUrl(sessionId, run.runId));
    source.addEventListener("report", (event) => {
      const update = JSON.parse((event as MessageEvent).data) as RunReportUpdate;
      setReport(update);
      if (update.status === "COMPLETED") {
        pollPackage(run.runId);
      }
    });
    source.onerror = () => source.close();

    return () => source.close();
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
    setBusy(true);
    setError(null);
    try {
      const result = await apiClient<GenerationRunResult>(`/sessions/${sessionId}/generate`, {
        method: "POST",
      });
      setRun(result);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 402
          ? "Not enough credits for a full blueprint run (needs 50)."
          : e instanceof ApiError
            ? e.message
            : "Couldn't start generation. Try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  const canAfford = balance ? balance.creditsBalance >= FULL_BLUEPRINT_CREDITS : true;

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
          <div className="flex flex-col gap-2">
            <Button onClick={generate} disabled={busy || !canAfford} className="self-start">
              {busy ? "Starting…" : `Generate blueprint (${FULL_BLUEPRINT_CREDITS} credits)`}
            </Button>
            {!canAfford && (
              <Alert variant="destructive">
                <AlertDescription>
                  Not enough credits ({balance?.creditsBalance ?? 0}/{FULL_BLUEPRINT_CREDITS}).
                </AlertDescription>
              </Alert>
            )}
          </div>
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
            {report.status === "IN_PROGRESS" && <Progress value={undefined} className="h-1.5" />}
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
    </Card>
  );
}
