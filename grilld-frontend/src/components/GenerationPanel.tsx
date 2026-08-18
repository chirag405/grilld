"use client";

import { useEffect, useRef, useState } from "react";
import { apiClient } from "@/lib/api-client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Loader } from "@/components/ui/loader";
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
 * blueprint, watch it happen, download the package. Polls the Run Report
 * every 2s rather than opening the live SSE stream (RunReportController's
 * /events) - a real, working view of progress, not a fake "coming soon"
 * placeholder, but not yet the diff-highlighting live canvas the spec
 * describes either. See LEARNING.md's Phase 9 note.
 */
export function GenerationPanel({ sessionId }: { sessionId: string }) {
  const [tier, setTier] = useState<ScaleCalibrationResult | null>(null);
  const [balance, setBalance] = useState<BillingBalance | null>(null);
  const [run, setRun] = useState<GenerationRunResult | null>(null);
  const [report, setReport] = useState<RunReportUpdate | null>(null);
  const [pkg, setPkg] = useState<PackageStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    apiClient<BillingBalance>("/billing/balance").then(setBalance).catch(() => {});
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

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
      pollRef.current = setInterval(() => pollReport(result.runId), 2000);
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

  async function pollReport(runId: string) {
    try {
      const update = await apiClient<RunReportUpdate>(`/sessions/${sessionId}/runs/${runId}/report`);
      setReport(update);
      if (update.status === "COMPLETED") {
        if (pollRef.current) clearInterval(pollRef.current);
        pollPackage(runId);
      } else if (update.status === "FAILED") {
        if (pollRef.current) clearInterval(pollRef.current);
      }
    } catch {
      if (pollRef.current) clearInterval(pollRef.current);
    }
  }

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
              <pre className="max-h-64 overflow-y-auto whitespace-pre-wrap font-mono text-xs leading-relaxed text-ink">
                {report.runReportMd}
              </pre>
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
