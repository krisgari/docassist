import type { AgentResult, Job, ToolTrace } from "./api";

export type Message = {
  id: string;
  role: "user" | "assistant";
  text: string;
  pending?: boolean;
  status?: string;
  tools: ToolTrace[];
  result?: AgentResult;
  job?: Job;
  error?: string;
  feedback?: 1 | -1;
};

export type Selection = { messageId: string; citation?: number; tab: "sources" | "how" } | null;

export const STARTERS: Record<string, string[]> = {
  alice: [
    "What do I do in a SEV1 payments incident?",
    "How many weeks of parental leave does a primary caregiver get?",
    "What is the salary range for a Staff Engineer?",
  ],
  bob: ["Who approves a 25% discount?", "Is Halcyon SOC 2 certified?", "What was Q3 2026 revenue?"],
  carol: [
    "What is the base salary range for a Staff Engineer?",
    "When do merit increases take effect?",
    "Summarize everything about parental leave",
  ],
  dave: ["What was Q3 2026 revenue and what are the main risks?", "Who approves discounts above 30%?", "What is our standard vendor liability cap?"],
  erin: [
    "How many weeks of parental leave do I get?",
    "What are the password and MFA rules?",
    "What is the guest Wi-Fi network called?",
  ],
};
