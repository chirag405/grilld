"use client";

import { useState } from "react";
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
import { PromptInput, PromptInputActions, PromptInputTextarea } from "@/components/ui/prompt-input";
import { Loader } from "@/components/ui/loader";
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
      setTurns([{ role: "assistant", content: result.question, whyAsking: result.whyAsking }]);
      setInputMode(result.inputMode);
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
      <main className="flex min-h-dvh items-center justify-center bg-paper px-6">
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
      </main>
    );
  }

  const currentInputMode = concluded ? null : inputMode;

  return (
    <main className="grid min-h-dvh grid-cols-1 bg-paper lg:grid-cols-[1fr_360px]">
      <section className="flex min-h-dvh flex-col">
        <ChatContainerRoot className="flex-1 px-6 py-10 sm:px-12">
          <ChatContainerContent className="mx-auto flex w-full max-w-2xl flex-col gap-6">
            {turns.map((turn, i) => (
              <Message key={i} className={turn.role === "user" ? "justify-end" : "justify-start"}>
                <div className="flex max-w-[85%] flex-col gap-1">
                  {turn.role === "assistant" && turn.whyAsking && (
                    <p className="px-1 text-xs italic text-ink-soft">{turn.whyAsking}</p>
                  )}
                  <MessageContent
                    className={
                      turn.role === "user"
                        ? "bg-ink text-paper"
                        : "bg-secondary text-ink"
                    }
                  >
                    {turn.content}
                  </MessageContent>
                </div>
              </Message>
            ))}

            {answering && (
              <Message className="justify-start">
                <div className="rounded-lg bg-secondary px-3 py-2">
                  <Loader variant="typing" size="sm" />
                </div>
              </Message>
            )}

            {concluded && (
              <div className="pt-2">
                <p className="mb-4 text-xl font-semibold text-ink">That&rsquo;s enough to work with.</p>
                <GenerationPanel sessionId={sessionId} />
              </div>
            )}
          </ChatContainerContent>
        </ChatContainerRoot>

        {currentInputMode && (
          <div className="mx-auto w-full max-w-2xl px-6 pb-8 sm:px-12">
            {error && (
              <Alert variant="destructive" className="mb-3">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <AnswerForm inputMode={currentInputMode} onSubmit={submitAnswer} submitting={answering} />
          </div>
        )}
      </section>

      <div className="border-t border-line lg:border-l lg:border-t-0">
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
