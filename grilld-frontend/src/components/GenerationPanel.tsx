"use client";

import { useEffect, useRef, useState } from "react";
import { apiClient } from "@/lib/api-client";
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
 * describes either. See LEARNING.md's Phase 9 note: that's real, scoped
 * follow-up work, not something worth faking with a polling loop dressed up
 * to look like it.
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
    <div className="flex flex-col gap-6 border-t border-ink/15 pt-6">
      <div className="flex items-center justify-between font-mono text-xs text-ink-soft">
        <span>interview complete</span>
        {balance && <span>{balance.creditsBalance} credits</span>}
      </div>

      {!tier ? (
        <button
          onClick={calibrate}
          disabled={busy}
          className="self-start rounded-md border border-ink px-5 py-2.5 font-display text-sm font-medium text-ink transition-colors hover:bg-ink hover:text-paper disabled:opacity-40"
        >
          {busy ? "Calibrating…" : "Calibrate scale"}
        </button>
      ) : (
        <div className="flex flex-col gap-1">
          <p className="font-display text-sm font-medium text-ink">Scale: {tier.tier}</p>
          <p className="text-sm text-ink-soft">{tier.reasoning}</p>
        </div>
      )}

      {tier && !run && (
        <div className="flex flex-col gap-2">
          <button
            onClick={generate}
            disabled={busy || !canAfford}
            className="self-start rounded-md bg-rust px-5 py-2.5 font-display text-sm font-medium text-paper shadow-[3px_3px_0_0_var(--color-ink)] transition-transform hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy ? "Starting…" : `Generate blueprint (${FULL_BLUEPRINT_CREDITS} credits)`}
          </button>
          {!canAfford && (
            <p className="text-sm text-danger">
              Not enough credits ({balance?.creditsBalance ?? 0}/{FULL_BLUEPRINT_CREDITS}).
            </p>
          )}
        </div>
      )}

      {error && <p className="text-sm text-danger">{error}</p>}

      {report && (
        <div className="blueprint-sheet border border-ink/15 p-4">
          <p className="mb-2 font-mono text-xs uppercase tracking-widest text-ink-soft">
            Run Report — {report.status}
          </p>
          <pre className="whitespace-pre-wrap font-mono text-xs leading-relaxed text-ink">
            {report.runReportMd ?? "Assembling…"}
          </pre>
          {report.status === "FAILED" && report.failureReason && (
            <p className="mt-2 text-sm text-danger">{report.failureReason}</p>
          )}
        </div>
      )}

      {pkg?.status === "READY" && run && (
        <a
          href={`/api/proxy/sessions/${sessionId}/runs/${run.runId}/package/download`}
          className="self-start rounded-md bg-ink px-5 py-2.5 font-display text-sm font-medium text-paper transition-opacity hover:opacity-90"
        >
          Download blueprint (.zip)
        </a>
      )}
    </div>
  );
}
