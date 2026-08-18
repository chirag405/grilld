// Mirrors grilld-backend's response DTOs exactly (SessionService, GenerationService,
// BillingController) - see each Java record's own Javadoc for the field-level "why."

export type InputMode = "text" | "number" | "chips" | "voice_primary";

export interface SessionStartResult {
  sessionId: string;
  question: string;
  inputMode: InputMode;
  whyAsking: string;
  chipOptions: string[];
}

export interface TurnAnswerResult {
  question: string | null;
  concluded: boolean;
  inputMode: InputMode | null;
  whyAsking: string | null;
  chipOptions: string[];
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
