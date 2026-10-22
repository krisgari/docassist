// Typed client for the query API. Streams use fetch + ReadableStream (EventSource can't send an auth header).

export type Person = { userId: string; name: string; title: string; groups: string[] };

export type Citation = {
  n: number;
  docId: string;
  title: string;
  url: string;
  section: string;
  modified: string | null;
  status: string;
  sourceSystem: string;
  snippet: string;
};

export type ToolTrace = { tool: string; input: Record<string, unknown>; summary: string; ms: number; error: boolean };
export type GuardrailEvent = { type: string; detail: string };
export type UsageLine = { model: string; inputTokens: number; outputTokens: number; cacheReadTokens: number; costUsd: number };

export type AgentResult = {
  answer: string;
  outcome: "answered" | "not_found" | "partial" | "error";
  citations: Citation[];
  trace: ToolTrace[];
  guardrails: GuardrailEvent[];
  droppedByPermissionCheck: string[];
  passagesConsidered: number;
  usage: UsageLine[];
  costUsd: number;
  latencyMs: number;
  auditId: string | null;
  jobId: string | null;
};

export type Job = {
  jobId: string;
  type: string;
  status: "queued" | "running" | "succeeded" | "failed";
  progress: number;
  note: string | null;
  result: { summary: string; sources: { n: number; docId: string; title: string; modified: string }[] } | null;
  error: string | null;
  costUsd: number;
  createdAt: string;
  reused: boolean;
};

export type Turn = { role: "user" | "assistant"; content: string };

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, message);
  }
  return res.json() as Promise<T>;
}

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

export const api = {
  people: () => fetch("/auth/demo-users").then((r) => json<Person[]>(r)),

  signIn: (userId: string) =>
    fetch("/auth/demo-login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId }),
    }).then((r) => json<{ token: string; user: Person }>(r)),

  summarize: (token: string, topic: string) =>
    fetch("/api/jobs/summarize", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...auth(token) },
      body: JSON.stringify({ topic }),
    }).then((r) => json<Job>(r)),

  jobs: (token: string) => fetch("/api/jobs", { headers: auth(token) }).then((r) => json<Job[]>(r)),

  job: (token: string, jobId: string) => fetch(`/api/jobs/${jobId}`, { headers: auth(token) }).then((r) => json<Job>(r)),

  feedback: (token: string, auditId: string, rating: 1 | -1, reason?: string) =>
    fetch("/api/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...auth(token) },
      body: JSON.stringify({ auditId, rating, reason }),
    }).then((r) => json<{ ok: boolean }>(r)),

  insights: (token: string) =>
    fetch("/api/admin/insights", { headers: auth(token) }).then((r) => json<Insights>(r)),
};

export type Insights = {
  totals: Record<string, number>;
  feedback: { up: number; down: number };
  guardrails: { type: string; n: number }[];
  index: {
    doc_id: string;
    title: string;
    status: string;
    sensitivity: string;
    modified_at: string;
    indexed_at: string;
    acl_entries: number;
    chunks: number;
    injection_flag: boolean;
  }[];
  recent: {
    audit_id: string;
    user_id: string;
    question: string;
    outcome: string;
    latency_ms: number;
    cost_usd: number;
    cited_doc_ids: string[];
    dropped_by_acl: string[];
    tool_calls: number;
    created_at: string;
    rating: number | null;
  }[];
};

/** Reads a text/event-stream body and calls onEvent(name, parsedData) for each event. */
export async function readSse(
  res: Response,
  onEvent: (event: string, data: unknown) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!res.ok || !res.body) {
    await json(res); // throws ApiError with the server's message
    return;
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (!signal?.aborted) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, idx);
      buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, "");
      let event = "message";
      const dataLines: string[] = [];
      for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
      }
      if (dataLines.length === 0) continue;
      const text = dataLines.join("\n");
      let data: unknown = text;
      try {
        data = JSON.parse(text);
      } catch {
        /* plain text event */
      }
      onEvent(event, data);
    }
  }
}

export function streamChat(
  token: string,
  question: string,
  history: Turn[],
  onEvent: (event: string, data: unknown) => void,
  signal?: AbortSignal,
) {
  return fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...auth(token) },
    body: JSON.stringify({ question, history }),
    signal,
  }).then((res) => readSse(res, onEvent, signal));
}

export function streamJob(token: string, jobId: string, onEvent: (event: string, data: unknown) => void, signal?: AbortSignal) {
  return fetch(`/api/jobs/${jobId}/events`, { headers: { Accept: "text/event-stream", ...auth(token) }, signal }).then((res) =>
    readSse(res, onEvent, signal),
  );
}
