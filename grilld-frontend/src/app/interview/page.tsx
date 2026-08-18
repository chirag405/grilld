"use client";

import { useState } from "react";
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
import { Loader } from "@/components/ui/loader";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Spotlight } from "@/components/ui/spotlight";
import { UserMenu } from "@/components/UserMenu";
import { ApiError, type InputMode, type SessionDetail } from "@/lib/types";

interface StartResponse {
  sessionId: string;
  question: string;
  inputMode: InputMode;
  whyAsking: string;
  chipOptions: string[];
}

interface AnswerResponse {
  question: string | null;
  concluded: boolean;
  inputMode: InputMode | null;
  whyAsking: string | null;
  chipOptions: string[];
}

interface Turn {
  role: "assistant" | "user";
  content: string;
  whyAsking?: string | null;
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
        { role: "assistant", content: result.question, whyAsking: result.whyAsking },
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
        setConcluded(true);
      } else if (result.question) {
        setTurns((t) => [...t, { role: "assistant", content: result.question!, whyAsking: result.whyAsking }]);
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

  if (!sessionId) {
    return (
      <main className="flex min-h-dvh flex-col bg-paper px-6">
        <TopNav />
        <div className="flex flex-1 items-center justify-center">
        <div className="flex w-full max-w-xl flex-col gap-5">
          <p className="font-mono text-xs uppercase tracking-[0.3em] text-accent-ink">start here</p>
          <h1 className="text-3xl font-semibold text-ink">What are you building?</h1>
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
                {starting ? "Starting…" : "Start the interview"}
              </Button>
            </PromptInputActions>
          </PromptInput>
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
        </div>
        </div>
      </main>
    );
  }

  const currentInputMode = concluded ? null : inputMode;

  return (
    <main className="grid h-dvh grid-cols-1 grid-rows-[minmax(0,1fr)] overflow-hidden overscroll-none bg-paper lg:grid-cols-[1fr_360px]">
      <section className="relative flex h-full min-h-0 flex-col overflow-hidden">
        <Spotlight className="-top-24 left-1/3 opacity-60" fill="var(--color-accent)" />
        <TopNav />
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
        <Button onClick={onConfirm} className="self-start">
          Looks right — generate my blueprint
        </Button>
      </CardContent>
    </Card>
  );
}

function TopNav() {
  return (
    <header className="flex shrink-0 items-center justify-between border-b border-line/70 bg-paper/80 px-4 py-3 backdrop-blur-sm sm:px-6">
      <Link href="/interview" className="text-sm font-semibold tracking-tight text-ink">
        grilld
      </Link>
      <div className="flex items-center gap-4">
        <Link href="/billing" className="font-mono text-xs uppercase tracking-widest text-ink-soft hover:text-ink">
          billing
        </Link>
        <UserMenu />
      </div>
    </header>
  );
}
