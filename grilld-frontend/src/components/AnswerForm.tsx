"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { PromptSuggestion } from "@/components/ui/prompt-suggestion";
import {
  PromptInput,
  PromptInputActions,
  PromptInputTextarea,
} from "@/components/ui/prompt-input";
import type { InputMode } from "@/lib/types";

const SKIP_PHRASE = "I'd like to skip this question for now.";

/**
 * Renders the right control for the Interrogator's chosen inputMode
 * (interrogation-engine.md §2.2's "hybrid input, decided per question").
 *
 * chipOptions (when the Interrogator actually supplied concrete ones for
 * this specific question) render as toggleable pills - multi-select, since
 * more than one can be true at once ("budget" AND "compliance" as
 * non-negotiables, say) - alongside free text, which always stays
 * available as an override/addition, never replaced by the chips.
 * inputMode="chips" with an empty chipOptions list (the Interrogator
 * couldn't write real options for this question) falls back to plain text -
 * inventing generic placeholder labels would misrepresent what the AI
 * actually asked for. voice_primary falls back to text too: no
 * speech-to-text is wired up anywhere in this stack yet (see LEARNING.md's
 * Phase 9 note) - a mic button with nothing behind it would be a fake
 * affordance. Both are named, honest gaps, not silently faked here.
 *
 * "Skip" is real, not a fake affordance: the Interrogator already has a
 * first-class waived_slots concept it applies with its own judgment (not
 * word-matching), so sending a plain skip phrase as a normal answer lets
 * the real AI decide how to waive the slot - same submitAnswer path as any
 * other answer.
 */
export function AnswerForm({
  inputMode,
  chipOptions,
  onSubmit,
  submitting,
}: {
  inputMode: InputMode;
  chipOptions: string[];
  onSubmit: (answer: string) => void;
  submitting: boolean;
}) {
  const [value, setValue] = useState("");
  const [selectedChips, setSelectedChips] = useState<string[]>([]);

  function submit() {
    const trimmed = value.trim();
    if (!trimmed || submitting) return;
    onSubmit(trimmed);
    setValue("");
  }

  function skip() {
    if (submitting) return;
    onSubmit(SKIP_PHRASE);
    setValue("");
    setSelectedChips([]);
  }

  function toggleChip(option: string) {
    setSelectedChips((current) =>
      current.includes(option) ? current.filter((o) => o !== option) : [...current, option],
    );
  }

  function submitChips() {
    if (submitting || selectedChips.length === 0) return;
    onSubmit(selectedChips.join(", "));
    setSelectedChips([]);
    setValue("");
  }

  if (inputMode === "chips" && chipOptions.length > 0) {
    return (
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap gap-2">
          {chipOptions.map((option) => (
            <PromptSuggestion
              key={option}
              variant={selectedChips.includes(option) ? "default" : "outline"}
              onClick={() => toggleChip(option)}
            >
              {option}
            </PromptSuggestion>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <Input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="Or type your own answer"
            className="flex-1"
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
            }}
          />
          <Button variant="ghost" size="sm" onClick={skip} disabled={submitting}>
            Skip
          </Button>
          {value.trim() ? (
            <Button onClick={submit} disabled={submitting}>
              {submitting ? "Sending…" : "Answer"}
            </Button>
          ) : (
            <Button onClick={submitChips} disabled={submitting || selectedChips.length === 0}>
              {submitting ? "Sending…" : "Continue"}
            </Button>
          )}
        </div>
      </div>
    );
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
        <Button type="button" variant="ghost" size="sm" onClick={skip} disabled={submitting}>
          Skip
        </Button>
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
      <PromptInputActions className="justify-end gap-2 pt-2">
        <Button variant="ghost" size="sm" onClick={skip} disabled={submitting}>
          Skip
        </Button>
        <Button size="sm" onClick={submit} disabled={submitting || !value.trim()}>
          {submitting ? "Sending…" : "Answer"}
        </Button>
      </PromptInputActions>
    </PromptInput>
  );
}
