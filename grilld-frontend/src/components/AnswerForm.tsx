"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import {
  PromptInput,
  PromptInputActions,
  PromptInputTextarea,
} from "@/components/ui/prompt-input";
import type { InputMode } from "@/lib/types";

/**
 * Renders the right control for the Interrogator's chosen inputMode
 * (interrogation-engine.md §2.2's "hybrid input, decided per question").
 * "chips" and "voice_primary" both fall back to the text control here: the
 * backend's structured output doesn't carry concrete chip option values or
 * wire up any speech-to-text today (see LEARNING.md's Phase 9 note) - a
 * generic set of invented chip labels would misrepresent what the AI
 * actually asked for, and a mic button with no STT behind it would be a
 * fake affordance. Both are named, honest gaps, not silently faked here.
 */
export function AnswerForm({
  inputMode,
  onSubmit,
  submitting,
}: {
  inputMode: InputMode;
  onSubmit: (answer: string) => void;
  submitting: boolean;
}) {
  const [value, setValue] = useState("");

  function submit() {
    const trimmed = value.trim();
    if (!trimmed || submitting) return;
    onSubmit(trimmed);
    setValue("");
  }

  if (inputMode === "number") {
    return (
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className="flex items-center gap-2"
      >
        <Input
          type="number"
          inputMode="numeric"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          autoFocus
          placeholder="Your answer"
          className="flex-1"
        />
        <Button type="submit" disabled={submitting || !value.trim()}>
          {submitting ? "Sending…" : "Answer"}
        </Button>
      </form>
    );
  }

  return (
    <PromptInput value={value} onValueChange={setValue} onSubmit={submit} isLoading={submitting}>
      <PromptInputTextarea
        autoFocus
        placeholder={inputMode === "voice_primary" ? "Speak, or type your answer" : "Your answer"}
      />
      <PromptInputActions className="justify-end pt-2">
        <Button size="sm" onClick={submit} disabled={submitting || !value.trim()}>
          {submitting ? "Sending…" : "Answer"}
        </Button>
      </PromptInputActions>
    </PromptInput>
  );
}
