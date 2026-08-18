// Mirrors grilld-backend's response DTOs exactly (SessionService, GenerationService,
// BillingController) - see each Java record's own Javadoc for the field-level "why."

export type InputMode = "text" | "number" | "chips" | "voice_primary";

export interface UserProfile {
  id: string;
  email: string;
  name: string | null;
  pictureUrl: string | null;
  plan: "FREE" | "STARTER" | "BUILDER" | "PRO" | "TEAM";
  creditsBalance: number;
  createdAt: string;
}

export interface SessionStartResult {
  sessionId: string;
  question: string;
  inputMode: InputMode;
  whyAsking: string;
  chipOptions: string[];
  intent: UserIntent;
  assistantMessage: string | null;
  reasoningTrace: ReasoningTrace;
}

export type UserIntent = "ANSWER" | "QUESTION" | "CORRECTION" | "SKIP" | "FINISH" | "UNRELATED";

export interface ReasoningTrace {
  summary: string;
  decisions: string[];
  assumptions: string[];
}

export interface TurnAnswerResult {
  question: string | null;
  concluded: boolean;
  inputMode: InputMode | null;
  whyAsking: string | null;
  chipOptions: string[];
  intent: UserIntent;
  assistantMessage: string | null;
  reasoningTrace: ReasoningTrace;
}

export interface SlotView {
  slotKey: string;
  description: string;
  status: "OPEN" | "FILLED" | "ASSUMED" | "WAIVED" | "BLOCKED";
  value: string | null;
  importance: number;
}

export interface SessionDetail {
  sessionId: string;
  status: "ACTIVE" | "READY_FOR_GENERATION" | "COMPLETED" | "ABANDONED";
  rawIdea: string;
  briefJson: string;
  scaleTier: string | null;
  scaleTierReasoning: string | null;
  slots: SlotView[];
  reasoningTraces: ReasoningTrace[];
}

export interface SessionSummary {
  sessionId: string;
  rawIdea: string;
  status: "ACTIVE" | "READY_FOR_GENERATION" | "COMPLETED" | "ABANDONED";
  createdAt: string;
  updatedAt: string;
}

export interface TurnHistoryEntry {
  turnNumber: number;
  questionText: string;
  answerText: string | null;
  assistantMessage: string | null;
  inputMode: InputMode | null;
  reasoningTrace: ReasoningTrace | null;
}

export interface ScaleCalibrationResult {
  tier: "T0" | "T1" | "T2" | "T3";
  reasoning: string;
  signals: string[];
}

export interface GenerationRunResult {
  runId: string;
  status: string;
  files: Record<string, string>;
}

export interface RunReportUpdate {
  status: "IN_PROGRESS" | "COMPLETED" | "FAILED";
  runReportMd: string | null;
  failureReason: string | null;
  completedAgents: number;
  totalAgents: number;
  currentStep: string;
  steps: GenerationStep[];
  completedDocuments: string[];
}

export interface GenerationStep {
  agentName: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  narration: string | null;
  documents: string[];
}

export interface PackageStatusResponse {
  packageId: string;
  status: "PENDING" | "READY" | "FAILED";
  documentPaths: string[];
}

export const FULL_BLUEPRINT_CREDITS = 50;

export interface BillingBalance {
  creditsBalance: number;
  recentTransactions: { delta: number; reason: string; createdAt: string }[];
}

export type CreditPackage = "STARTER" | "TOPUP";

export interface CheckoutUrlResponse {
  checkoutUrl: string;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
