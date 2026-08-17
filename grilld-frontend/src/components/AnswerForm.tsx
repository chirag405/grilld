"use client";

import { useState, type FormEvent } from "react";
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

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = value.trim();
    if (!trimmed || submitting) return;
    onSubmit(trimmed);
    setValue("");
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:items-end">
      {inputMode === "number" ? (
        <input
          type="number"
          inputMode="numeric"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          autoFocus
          placeholder="Your answer"
          className="w-full flex-1 border-b-2 border-ink/20 bg-transparent py-2 font-display text-xl text-ink outline-none transition-colors focus:border-blueprint"
        />
      ) : (
        <textarea
          value={value}
          onChange={(e) => setValue(e.target.value)}
          autoFocus
          rows={2}
          placeholder={inputMode === "voice_primary" ? "Speak, or type your answer" : "Your answer"}
          className="w-full flex-1 resize-none border-b-2 border-ink/20 bg-transparent py-2 font-display text-xl text-ink outline-none transition-colors focus:border-blueprint"
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              handleSubmit(e);
            }
          }}
        />
      )}
      <button
        type="submit"
        disabled={submitting || !value.trim()}
        className="shrink-0 rounded-md bg-ink px-5 py-2.5 font-display text-sm font-medium text-paper transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {submitting ? "Sending…" : "Answer"}
      </button>
    </form>
  );
}
