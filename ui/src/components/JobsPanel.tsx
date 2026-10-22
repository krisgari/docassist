import { useState, type FormEvent } from "react";
import type { Job } from "../api";
import { AnswerText } from "./AnswerText";

export function JobsPanel({
  jobs,
  onStart,
  busy,
}: {
  jobs: Job[];
  onStart: (topic: string) => void;
  busy: boolean;
}) {
  const [topic, setTopic] = useState("");
  const [open, setOpen] = useState<string | null>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!topic.trim()) return;
    onStart(topic.trim());
    setTopic("");
  };

  return (
    <section className="jobs" aria-label="Background jobs">
      <h2 className="rail-title">Background jobs</h2>
      <form onSubmit={submit} className="job-form">
        <label htmlFor="topic" className="sr-only">
          Topic to summarize
        </label>
        <input id="topic" value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="Summarize a topic" maxLength={300} />
        <button type="submit" disabled={busy || !topic.trim()}>
          Start
        </button>
      </form>
      {jobs.length === 0 && <p className="quiet small">Long summaries run here so the chat stays fast.</p>}
      <ul className="job-list">
        {jobs.map((j) => (
          <li key={j.jobId} className={"job job-" + j.status}>
            <button type="button" className="job-head" onClick={() => setOpen(open === j.jobId ? null : j.jobId)} aria-expanded={open === j.jobId}>
              <span className="job-status">{statusText(j)}</span>
              <span className="job-note">{j.note ?? ""}</span>
              <span className="bar" aria-hidden="true">
                <span style={{ width: `${Math.max(2, j.progress)}%` }} />
              </span>
            </button>
            {open === j.jobId && j.result && (
              <div className="job-result">
                <AnswerText text={j.result.summary} citable={new Set<number>()} onCite={() => undefined} />
                <ol className="job-sources">
                  {j.result.sources.map((s) => (
                    <li key={s.n} value={s.n}>
                      {s.title} <span className="quiet">({s.modified})</span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
            {open === j.jobId && j.error && <p className="msg-error">{j.error}</p>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function statusText(j: Job) {
  switch (j.status) {
    case "queued":
      return "Queued";
    case "running":
      return `Running, ${j.progress}%`;
    case "succeeded":
      return "Ready";
    case "failed":
      return "Failed";
  }
}
