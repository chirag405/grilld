"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiClient } from "@/lib/api-client";
import { TitleBlockPanel } from "@/components/TitleBlockPanel";
import { SlotList } from "@/components/SlotList";
import { AnswerForm } from "@/components/AnswerForm";
import { GenerationPanel } from "@/components/GenerationPanel";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  ChatContainerContent,
  ChatContainerRoot,
} from "@/components/ui/chat-container";
import { Message, MessageContent } from "@/components/ui/message";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { PromptInput, PromptInputActions, PromptInputTextarea } from "@/components/ui/prompt-input";
import { PromptSuggestion } from "@/components/ui/prompt-suggestion";
import { Loader } from "@/components/ui/loader";
import { Reasoning, ReasoningContent, ReasoningTrigger } from "@/components/ui/reasoning";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Spotlight } from "@/components/ui/spotlight";
import { UserMenu } from "@/components/UserMenu";
import {
  ApiError,
  type InputMode,
  type ReasoningTrace,
  type SessionDetail,
  type SessionSummary,
  type TurnHistoryEntry,
  type UserIntent,
} from "@/lib/types";

const EXAMPLE_IDEAS = [
  "A tool for freelancers to track unpaid invoices",
  "A habit tracker for small teams that DMs weekly nudges",
  "An internal dashboard that turns support tickets into a triage queue",
];

const STATUS_LABEL: Record<SessionSummary["status"], string> = {
  ACTIVE: "in interview",
  READY_FOR_GENERATION: "ready to generate",
  COMPLETED: "blueprint ready",
  ABANDONED: "abandoned",
};

function relativeTime(iso: string) {
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

interface StartResponse {
  sessionId: string;
  question: string;
  inputMode: InputMode;
  whyAsking: string;
  chipOptions: string[];
  intent: UserIntent;
  assistantMessage: string | null;
  reasoningTrace: ReasoningTrace;
}

interface AnswerResponse {
  question: string | null;
  concluded: boolean;
  inputMode: InputMode | null;
  whyAsking: string | null;
  chipOptions: string[];
  intent: UserIntent;
  assistantMessage: string | null;
  reasoningTrace: ReasoningTrace;
}

interface Turn {
  role: "assistant" | "user";
  content: string;
  whyAsking?: string | null;
  reasoning?: ReasoningTrace;
}

export default function InterviewPage() {
  const [rawIdea, setRawIdea] = useState("");
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [inputMode, setInputMode] = useState<InputMode>("text");
  const [chipOptions, setChipOptions] = useState<string[]>([]);
  const [concluded, setConcluded] = useState(false);
  const [confirmedToGenerate, setConfirmedToGenerate] = useState(false);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [answering, setAnswering] = useState(false);
  const [pastSessions, setPastSessions] = useState<SessionSummary[] | null>(null);
  const [resuming, setResuming] = useState<string | null>(null);

  useEffect(() => {
    if (sessionId) return;
    apiClient<SessionSummary[]>("/sessions")
      .then(setPastSessions)
      .catch(() => setPastSessions([]));
  }, [sessionId]);

  async function refreshDetail(id: string) {
    try {
      const d = await apiClient<SessionDetail>(`/sessions/${id}`);
      setDetail(d);
    } catch {
      // Non-fatal - the brief panel just stays stale until the next successful refresh.
    }
  }

  async function startInterview() {
    if (!rawIdea.trim() || starting) return;
    setStarting(true);
    setError(null);
    try {
      const idea = rawIdea.trim();
      const result = await apiClient<StartResponse>("/sessions", {
        method: "POST",
        body: JSON.stringify({ rawIdea: idea }),
      });
      setSessionId(result.sessionId);
      setTurns([
        { role: "user", content: idea },
        { role: "assistant", content: result.question, whyAsking: result.whyAsking, reasoning: result.reasoningTrace },
      ]);
      setInputMode(result.inputMode);
      setChipOptions(result.chipOptions);
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
    setTurns((t) => [...t, { role: "user", content: answerText }]);
    try {
      const result = await apiClient<AnswerResponse>(`/sessions/${sessionId}/answer`, {
        method: "POST",
        body: JSON.stringify({ answerText }),
      });
      if (result.concluded) {
        if (result.assistantMessage) {
          setTurns((t) => [...t, { role: "assistant", content: result.assistantMessage!, reasoning: result.reasoningTrace }]);
        }
        setConcluded(true);
      } else if (result.question) {
        const content = result.assistantMessage
          ? `${result.assistantMessage}\n\n${result.question}`
          : result.question;
        setTurns((t) => [...t, { role: "assistant", content, whyAsking: result.whyAsking, reasoning: result.reasoningTrace }]);
        setInputMode(result.inputMode ?? "text");
        setChipOptions(result.chipOptions);
      }
      await refreshDetail(sessionId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't submit that answer. Try again.");
    } finally {
      setAnswering(false);
    }
  }

  async function finishInterview() {
    if (!sessionId || answering) return;
    setAnswering(true);
    setError(null);
    try {
      await apiClient<void>(`/sessions/${sessionId}/force-conclude`, { method: "POST" });
      setConcluded(true);
      await refreshDetail(sessionId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't finish the interview. Try again.");
    } finally {
      setAnswering(false);
    }
  }

  async function editSlot(slotKey: string, value: string) {
    if (!sessionId) return;
    await apiClient<void>(`/sessions/${sessionId}/slots/${encodeURIComponent(slotKey)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
    await refreshDetail(sessionId);
  }

  async function resumeSession(summary: SessionSummary) {
    if (resuming) return;
    setResuming(summary.sessionId);
    setError(null);
    try {
      const [history, detailResult] = await Promise.all([
        apiClient<TurnHistoryEntry[]>(`/sessions/${summary.sessionId}/turns`),
        apiClient<SessionDetail>(`/sessions/${summary.sessionId}`),
      ]);
      const rebuilt: Turn[] = [{ role: "user", content: summary.rawIdea }];
      let openInputMode: InputMode = "text";
      history.forEach((entry, index) => {
        const content = entry.assistantMessage
          ? `${entry.assistantMessage}\n\n${entry.questionText}`
          : entry.questionText;
        rebuilt.push({ role: "assistant", content, reasoning: entry.reasoningTrace ?? undefined });
        if (entry.answerText) {
          rebuilt.push({ role: "user", content: entry.answerText });
        } else if (index === history.length - 1) {
          // Resumed mid-question - chip_options aren't persisted per turn (only
          // the live NextQuestion carries them), so a resumed chip question
          // honestly falls back to free text instead of fabricating options.
          openInputMode = entry.inputMode && entry.inputMode !== "chips" ? entry.inputMode : "text";
        }
      });
      setSessionId(summary.sessionId);
      setTurns(rebuilt);
      setDetail(detailResult);
      setChipOptions([]);
      const isConcluded = summary.status !== "ACTIVE";
      setConcluded(isConcluded);
      setConfirmedToGenerate(false);
      if (!isConcluded) setInputMode(openInputMode);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't resume that conversation. Try again.");
    } finally {
      setResuming(null);
    }
  }

  if (!sessionId) {
    return (
      <main className="relative flex min-h-dvh flex-col overflow-hidden bg-paper px-6">
        <Spotlight className="-top-32 left-1/4 opacity-50" fill="var(--color-accent)" />
        <TopNav />
        <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-10 py-16">
          <div className="flex flex-col gap-5">
            <p className="font-mono text-xs uppercase tracking-[0.3em] text-accent-ink">start here</p>
            <h1 className="text-3xl font-semibold text-ink sm:text-4xl">What are you building?</h1>
            <p className="max-w-lg text-sm text-ink-soft">
              Describe it in a sentence or two. Grilld asks a handful of sharp follow-ups, then turns
              the answers into a full technical blueprint - architecture, roadmap, and a ready-to-run agent kit.
            </p>
            <PromptInput
              value={rawIdea}
              onValueChange={setRawIdea}
              onSubmit={startInterview}
              isLoading={starting}
            >
              <PromptInputTextarea
                autoFocus
                rows={3}
                placeholder="A tool for freelancers to track unpaid invoices..."
              />
              <PromptInputActions className="justify-end pt-2">
                <Button onClick={startInterview} disabled={starting || !rawIdea.trim()}>
                  {starting ? "Starting…" : "Start the grill"}
                </Button>
              </PromptInputActions>
            </PromptInput>
            <div className="flex flex-wrap gap-2">
              {EXAMPLE_IDEAS.map((idea) => (
                <PromptSuggestion key={idea} variant="outline" onClick={() => setRawIdea(idea)}>
                  {idea}
                </PromptSuggestion>
              ))}
            </div>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
          </div>

          {pastSessions && pastSessions.length > 0 && (
            <div className="flex flex-col gap-3">
              <p className="font-mono text-xs uppercase tracking-[0.3em] text-ink-soft">your conversations</p>
              <ul className="flex flex-col gap-2">
                {pastSessions.map((summary) => (
                  <li key={summary.sessionId}>
                    <button
                      type="button"
                      onClick={() => resumeSession(summary)}
                      disabled={resuming !== null}
                      className="flex w-full items-center justify-between gap-4 rounded-lg border border-line bg-paper-raised px-4 py-3 text-left transition hover:border-accent/40 disabled:opacity-60"
                    >
                      <div className="min-w-0">
                        <p className="truncate text-sm text-ink">{summary.rawIdea}</p>
                        <p className="mt-0.5 font-mono text-[11px] text-ink-soft">{relativeTime(summary.updatedAt)}</p>
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <Badge variant="outline" className="text-[10px]">{STATUS_LABEL[summary.status]}</Badge>
                        {resuming === summary.sessionId && <Loader size="sm" />}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </main>
    );
  }

  const currentInputMode = concluded ? null : inputMode;

  return (
    <main className="grid h-dvh grid-cols-1 grid-rows-[minmax(0,1fr)] overflow-hidden overscroll-none bg-paper lg:grid-cols-[1fr_360px]">
      <section className="relative flex h-full min-h-0 flex-col overflow-hidden">
        <Spotlight className="-top-24 left-1/3 opacity-60" fill="var(--color-accent)" />
        <TopNav
          onBackToConversations={() => {
            setSessionId(null);
            setTurns([]);
            setDetail(null);
            setConcluded(false);
            setConfirmedToGenerate(false);
            setError(null);
          }}
        />
        <ChatContainerRoot className="relative min-h-0 flex-1 px-6 py-10 sm:px-12">
          <ChatContainerContent className="mx-auto flex w-full max-w-2xl flex-col gap-6">
            {turns.map((turn, i) => (
              <Message
                key={i}
                className={
                  turn.role === "user"
                    ? "animate-fade-up justify-end"
                    : "animate-fade-up justify-start"
                }
              >
                {turn.role === "assistant" && (
                  <Avatar className="h-7 w-7 shrink-0">
                    <AvatarFallback className="bg-accent-soft text-xs font-semibold text-accent-ink">
                      G
                    </AvatarFallback>
                  </Avatar>
                )}
                <div className="flex max-w-[85%] flex-col gap-1">
                  <MessageContent
                    markdown={turn.role === "assistant"}
                    className={
                      turn.role === "user"
                        ? "bg-ink text-paper"
                        : "bg-secondary text-ink"
                    }
                  >
                    {turn.content}
                  </MessageContent>
                  {turn.role === "assistant" && turn.whyAsking && (
                    <Tooltip>
                      <TooltipTrigger className="self-start px-1 text-xs text-ink-soft/70 underline decoration-dotted underline-offset-2 hover:text-ink-soft">
                        why is Grilld asking this?
                      </TooltipTrigger>
                      <TooltipContent className="max-w-xs">{turn.whyAsking}</TooltipContent>
                    </Tooltip>
                  )}
                  {turn.role === "assistant" && turn.reasoning && (
                    <Reasoning className="px-1">
                      <ReasoningTrigger className="text-xs text-ink-soft/75">What Grilld understood</ReasoningTrigger>
                      <ReasoningContent contentClassName="mt-2 rounded-md border border-line bg-paper-raised p-3 text-xs">
                        <ReasoningTraceContent trace={turn.reasoning} />
                      </ReasoningContent>
                    </Reasoning>
                  )}
                </div>
              </Message>
            ))}

            {answering && (
              <Message className="animate-fade-up justify-start">
                <Avatar className="h-7 w-7 shrink-0">
                  <AvatarFallback className="bg-accent-soft text-xs font-semibold text-accent-ink">
                    G
                  </AvatarFallback>
                </Avatar>
                <div className="rounded-lg bg-secondary px-3 py-2">
                  <Loader variant="typing" size="sm" />
                </div>
              </Message>
            )}

            {concluded && !confirmedToGenerate && detail && (
              <BriefReview detail={detail} onConfirm={() => setConfirmedToGenerate(true)} />
            )}

            {concluded && confirmedToGenerate && (
              <div className="pt-2">
                <p className="mb-4 text-xl font-semibold text-ink">That&rsquo;s enough to work with.</p>
                <GenerationPanel sessionId={sessionId} />
              </div>
            )}
          </ChatContainerContent>
        </ChatContainerRoot>

        {currentInputMode && (
          <div className="mx-auto w-full max-w-2xl shrink-0 border-t border-line/70 bg-paper px-6 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 sm:px-12">
            {error && (
              <Alert variant="destructive" className="mb-3">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <AnswerForm
              inputMode={currentInputMode}
              chipOptions={chipOptions}
              onSubmit={submitAnswer}
              submitting={answering}
            />
            <button type="button" onClick={finishInterview} disabled={answering} className="mt-2 text-xs text-ink-soft underline decoration-dotted underline-offset-2 hover:text-ink disabled:pointer-events-none disabled:opacity-50">
              I have shared enough — finish the brief
            </button>
          </div>
        )}
      </section>

      <div className="hidden h-full min-h-0 overflow-hidden border-l border-line lg:block">
        {detail && (
          <TitleBlockPanel
            project={detail.rawIdea}
            scale={detail.scaleTier ?? "TBD"}
            status={concluded ? "ready for generation" : "in interview"}
          >
            <SlotList slots={detail.slots} onEdit={editSlot} />
          </TitleBlockPanel>
        )}
      </div>
    </main>
  );
}

/**
 * Between "the interview concluded" and actually spending credits, a plain
 * summary of what Grilld learned - the user confirms it's right (or notices
 * something's off) before generation starts, rather than finding out only
 * after the run.
 */
function BriefReview({ detail, onConfirm }: { detail: SessionDetail; onConfirm: () => void }) {
  const filled = detail.slots.filter((s) => s.status === "FILLED" || s.status === "ASSUMED");

  return (
    <Card className="max-w-2xl gap-4">
      <CardHeader className="px-5">
        <CardTitle>Here&rsquo;s what Grilld has so far</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-5">
        <div>
          <p className="font-mono text-[11px] uppercase tracking-widest text-ink-soft">idea</p>
          <p className="mt-1 text-sm text-ink">{detail.rawIdea}</p>
        </div>
        <ul className="flex flex-col gap-3">
          {filled.map((slot) => (
            <li key={slot.slotKey} className="flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <span className="text-sm text-ink">{slot.description}</span>
                {slot.status === "ASSUMED" && (
                  <Badge variant="outline" className="border-accent/20 bg-accent-soft text-accent-ink">
                    assumed
                  </Badge>
                )}
              </div>
              {slot.value && <p className="font-mono text-xs text-ink-soft">{slot.value}</p>}
            </li>
          ))}
        </ul>
        {detail.reasoningTraces.length > 0 && (
          <Reasoning open>
            <ReasoningTrigger className="text-sm font-medium text-ink">How Grilld reached this brief</ReasoningTrigger>
            <ReasoningContent contentClassName="mt-3 space-y-3 border-l-2 border-accent/30 pl-4">
              {detail.reasoningTraces.map((trace, index) => (
                <div key={`${trace.summary}-${index}`}>
                  <p className="text-sm font-medium text-ink">{trace.summary}</p>
                  <ReasoningTraceContent trace={trace} />
                </div>
              ))}
            </ReasoningContent>
          </Reasoning>
        )}
        <Button onClick={onConfirm} className="self-start">
          Looks right — generate my blueprint
        </Button>
      </CardContent>
    </Card>
  );
}

function ReasoningTraceContent({ trace }: { trace: ReasoningTrace }) {
  return (
    <div className="space-y-2 text-xs text-ink-soft">
      {trace.decisions.length > 0 && (
        <ul className="list-disc space-y-1 pl-4">{trace.decisions.map((item) => <li key={item}>{item}</li>)}</ul>
      )}
      {trace.assumptions.length > 0 && (
        <div><p className="font-medium text-ink">Assumptions</p><ul className="list-disc space-y-1 pl-4">{trace.assumptions.map((item) => <li key={item}>{item}</li>)}</ul></div>
      )}
    </div>
  );
}

function TopNav({ onBackToConversations }: { onBackToConversations?: () => void }) {
  return (
    <header className="flex shrink-0 items-center justify-between border-b border-line/70 bg-paper/80 px-4 py-3 backdrop-blur-sm sm:px-6">
      <div className="flex items-center gap-4">
        <Link href="/interview" className="text-sm font-semibold tracking-tight text-ink">
          grilld
        </Link>
        {onBackToConversations && (
          <button
            type="button"
            onClick={onBackToConversations}
            className="font-mono text-xs uppercase tracking-widest text-ink-soft hover:text-ink"
          >
            ← conversations
          </button>
        )}
      </div>
      <div className="flex items-center gap-4">
        <Link href="/billing" className="font-mono text-xs uppercase tracking-widest text-ink-soft hover:text-ink">
          billing
        </Link>
        <UserMenu />
      </div>
    </header>
  );
}
