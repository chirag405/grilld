"use client";

import { useEffect, useRef, useState } from "react";
import { Mic, Square } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ApiErrorBody, TranscriptionResult } from "@/lib/types";

/**
 * The mic half of AnswerForm's voice_primary mode. Records with MediaRecorder,
 * uploads the clip to TranscriptionController, and hands the transcribed text
 * back to the caller to drop into the textarea for the user to review/edit
 * before submitting - it never auto-submits an answer it didn't type itself.
 *
 * No speech-to-text provider is wired up on the backend yet (grilld.voice.provider
 * defaults to "none" - see UnconfiguredTranscriptionService), so today this
 * mostly demonstrates the honest-failure path: record, upload, get a clear
 * "voice input isn't turned on yet" message back, fall back to typing. The
 * recording UI itself doesn't change once a real provider is configured.
 *
 * Deliberately not routed through apiClient() - that helper always sets
 * Content-Type: application/json when a body is present, which would strip
 * the multipart boundary FormData needs.
 */
export function VoiceRecorder({
  onTranscript,
  disabled,
}: {
  onTranscript: (text: string) => void;
  disabled?: boolean;
}) {
  const [recording, setRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);

  // Computed directly rather than via state - the browser's own capabilities
  // don't change during the component's lifetime, and this only ever renders
  // client-side (voice_primary mode appears after an async session fetch, never
  // during the server's initial render pass), so there's no hydration mismatch
  // to guard against by deferring the check into an effect.
  const supported =
    typeof navigator !== "undefined" &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof MediaRecorder !== "undefined";

  useEffect(() => {
    return () => {
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  async function startRecording() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mimeType = MediaRecorder.isTypeSupported("audio/webm") ? "audio/webm" : "";
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      chunksRef.current = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        void upload();
      };
      recorderRef.current = recorder;
      recorder.start();
      setRecording(true);
    } catch {
      setError("Microphone access was denied - type your answer instead.");
    }
  }

  function stopRecording() {
    recorderRef.current?.stop();
    setRecording(false);
  }

  async function upload() {
    if (chunksRef.current.length === 0) return;
    setBusy(true);
    try {
      const blob = new Blob(chunksRef.current, { type: chunksRef.current[0].type || "audio/webm" });
      const formData = new FormData();
      formData.append("audio", blob, "answer.webm");
      const response = await fetch("/api/proxy/voice/transcribe", { method: "POST", body: formData });
      if (!response.ok) {
        const body = (await response.json().catch(() => null)) as ApiErrorBody | null;
        throw new Error(body?.message ?? "Couldn't transcribe that - type your answer instead.");
      }
      const result = (await response.json()) as TranscriptionResult;
      onTranscript(result.text);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't transcribe that - type your answer instead.");
    } finally {
      setBusy(false);
    }
  }

  if (!supported) return null;

  return (
    <div className="flex items-center gap-2">
      <Button
        type="button"
        variant={recording ? "destructive" : "outline"}
        size="icon"
        className="h-8 w-8 rounded-full"
        disabled={disabled || busy}
        onClick={recording ? stopRecording : startRecording}
        aria-label={recording ? "Stop recording and transcribe" : "Record your answer"}
        title={recording ? "Stop recording and transcribe" : "Record your answer"}
      >
        {recording ? <Square className="h-3.5 w-3.5" /> : <Mic className="h-4 w-4" />}
      </Button>
      {busy && <span className="text-xs text-ink-soft">Transcribing…</span>}
      {error && <span className="text-xs text-danger">{error}</span>}
    </div>
  );
}
