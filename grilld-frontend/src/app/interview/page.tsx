"use client";

import { useState } from "react";
import { apiClient } from "@/lib/api-client";
import { TitleBlockPanel } from "@/components/TitleBlockPanel";
import { SlotList } from "@/components/SlotList";
import { AnswerForm } from "@/components/AnswerForm";
import { GenerationPanel } from "@/components/GenerationPanel";
import { ApiError, type InputMode, type SessionDetail } from "@/lib/types";

interface StartResponse {
  sessionId: string;
  question: string;
  inputMode: InputMode;
  whyAsking: string;
}

interface AnswerResponse {
  question: string | null;
  concluded: boolean;
  inputMode: InputMode | null;
  whyAsking: string | null;
}

export default function InterviewPage() {
  const [rawIdea, setRawIdea] = useState("");
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [question, setQuestion] = useState<string | null>(null);
  const [inputMode, setInputMode] = useState<InputMode>("text");
  const [whyAsking, setWhyAsking] = useState<string | null>(null);
  const [concluded, setConcluded] = useState(false);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [answering, setAnswering] = useState(false);

  async function refreshDetail(id: string) {
    try {
      const d = await apiClient<SessionDetail>(`/sessions/${id}`);
      setDetail(d);
    } catch {
      // Non-fatal - the brief panel just stays stale until the next successful refresh.
    }
  }

  async function startInterview(e: React.FormEvent) {
    e.preventDefault();
    if (!rawIdea.trim() || starting) return;
    setStarting(true);
    setError(null);
    try {
      const result = await apiClient<StartResponse>("/sessions", {
        method: "POST",
        body: JSON.stringify({ rawIdea: rawIdea.trim() }),
      });
      setSessionId(result.sessionId);
      setQuestion(result.question);
      setInputMode(result.inputMode);
      setWhyAsking(result.whyAsking);
      await refreshDetail(result.sessionId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't start the interview. Try again.");
    } finally {
      setStarting(false);
    }
  }

  async function submitAnswer(answerText: string) {
    if (!sessionId) return;
    setAnswering(true);
    setError(null);
    try {
      const result = await apiClient<AnswerResponse>(`/sessions/${sessionId}/answer`, {
        method: "POST",
        body: JSON.stringify({ answerText }),
      });
      if (result.concluded) {
        setConcluded(true);
        setQuestion(null);
      } else {
        setQuestion(result.question);
        setInputMode(result.inputMode ?? "text");
        setWhyAsking(result.whyAsking);
      }
      await refreshDetail(sessionId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't submit that answer. Try again.");
    } finally {
      setAnswering(false);
    }
  }

  if (!sessionId) {
    return (
      <main className="blueprint-sheet flex min-h-dvh items-center justify-center px-6">
        <form onSubmit={startInterview} className="flex w-full max-w-xl flex-col gap-5">
          <p className="font-mono text-xs uppercase tracking-[0.3em] text-blueprint">start here</p>
          <h1 className="font-display text-3xl font-semibold text-ink">What are you building?</h1>
          <textarea
            value={rawIdea}
            onChange={(e) => setRawIdea(e.target.value)}
            autoFocus
            rows={3}
            placeholder="A tool for freelancers to track unpaid invoices..."
            className="resize-none border-b-2 border-ink/20 bg-transparent py-2 font-display text-xl text-ink outline-none transition-colors focus:border-blueprint"
          />
          {error && <p className="text-sm text-danger">{error}</p>}
          <button
            type="submit"
            disabled={starting || !rawIdea.trim()}
            className="self-start rounded-md bg-ink px-6 py-3 font-display text-sm font-medium text-paper transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {starting ? "Starting…" : "Start the interview"}
          </button>
        </form>
      </main>
    );
  }

  return (
    <main className="grid min-h-dvh grid-cols-1 lg:grid-cols-[1fr_360px]">
      <section className="flex flex-col justify-center gap-6 px-6 py-16 sm:px-12">
        {question && (
          <div className="animate-fade-up flex max-w-2xl flex-col gap-4" key={question}>
            {whyAsking && <p className="text-sm italic text-ink-soft">{whyAsking}</p>}
            <h2 className="font-display text-2xl font-semibold leading-snug text-ink sm:text-3xl">
              {question}
            </h2>
            <AnswerForm inputMode={inputMode} onSubmit={submitAnswer} submitting={answering} />
            {error && <p className="text-sm text-danger">{error}</p>}
          </div>
        )}

        {concluded && (
          <div className="max-w-2xl">
            <p className="mb-6 font-display text-2xl font-semibold text-ink">
              That&rsquo;s enough to work with.
            </p>
            <GenerationPanel sessionId={sessionId} />
          </div>
        )}
      </section>

      <div className="border-t border-ink/15 lg:border-l lg:border-t-0">
        {detail && (
          <TitleBlockPanel
            project={detail.rawIdea}
            scale={detail.scaleTier ?? "TBD"}
            status={concluded ? "ready for generation" : "in interview"}
          >
            <SlotList slots={detail.slots} />
          </TitleBlockPanel>
        )}
      </div>
    </main>
  );
}
