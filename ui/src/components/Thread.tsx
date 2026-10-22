import { useEffect, useRef, useState, type FormEvent } from "react";
import type { Message, Selection } from "../types";
import { AnswerText } from "./AnswerText";

const OUTCOME_LABEL: Record<string, string> = {
  answered: "Answered from your documents",
  not_found: "Not found in documents you can open",
  partial: "Partial answer, quoted directly from sources",
  error: "Assistant unavailable",
};

export function Thread({
  messages,
  starters,
  busy,
  selection,
  onAsk,
  onSelect,
  onFeedback,
  personName,
}: {
  messages: Message[];
  starters: string[];
  busy: boolean;
  selection: Selection;
  onAsk: (q: string) => void;
  onSelect: (s: Selection) => void;
  onFeedback: (m: Message, rating: 1 | -1) => void;
  personName: string;
}) {
  const [draft, setDraft] = useState("");
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  const send = () => {
    const q = draft.trim();
    if (!q || busy) return;
    onAsk(q);
    setDraft("");
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    send();
  };

  return (
    <section className="thread" aria-label="Conversation">
      <div className="messages" aria-live="polite">
        {messages.length === 0 && (
          <div className="empty">
            <p className="empty-lead">
              Ask about policies, runbooks, pricing or plans. Answers cite the exact passage, and only use documents{" "}
              {personName} can open.
            </p>
            <ul className="starters">
              {starters.map((s) => (
                <li key={s}>
                  <button type="button" onClick={() => onAsk(s)} disabled={busy}>
                    {s}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {messages.map((m) =>
          m.role === "user" ? (
            <div key={m.id} className="msg msg-user">
              {m.text}
            </div>
          ) : (
            <article key={m.id} className={"msg msg-assistant" + (selection?.messageId === m.id ? " is-selected" : "")}>
              {m.pending && (
                <p className="working">
                  <span className="pulse" aria-hidden="true" />
                  {m.status ?? "Working"}
                  {m.tools.length > 0 && <span className="working-steps"> · {m.tools.length} step{m.tools.length > 1 ? "s" : ""}</span>}
                </p>
              )}
              {m.error && <p className="msg-error">{m.error}</p>}
              {m.result && (
                <>
                  <AnswerText
                    text={m.result.answer}
                    citable={new Set(m.result.citations.map((c) => c.n))}
                    active={selection?.messageId === m.id ? selection.citation : undefined}
                    onCite={(n) => onSelect({ messageId: m.id, citation: n, tab: "sources" })}
                  />
                  {m.job && (
                    <p className="job-note">
                      Background job started. Follow it under <strong>Background jobs</strong>.
                    </p>
                  )}
                  <footer className="answer-meta">
                    <span className={"outcome outcome-" + m.result.outcome}>{OUTCOME_LABEL[m.result.outcome]}</span>
                    <span className="meta-quiet">
                      {(m.result.latencyMs / 1000).toFixed(1)} s, ${m.result.costUsd.toFixed(4)}
                    </span>
                    <button type="button" className="link" onClick={() => onSelect({ messageId: m.id, tab: "how" })}>
                      How this was answered
                    </button>
                    {m.result.auditId && (
                      <span className="feedback" role="group" aria-label="Was this answer useful?">
                        <button
                          type="button"
                          aria-pressed={m.feedback === 1}
                          className={m.feedback === 1 ? "is-on" : ""}
                          onClick={() => onFeedback(m, 1)}
                        >
                          Useful
                        </button>
                        <button
                          type="button"
                          aria-pressed={m.feedback === -1}
                          className={m.feedback === -1 ? "is-on" : ""}
                          onClick={() => onFeedback(m, -1)}
                        >
                          Wrong
                        </button>
                      </span>
                    )}
                  </footer>
                </>
              )}
            </article>
          ),
        )}
        <div ref={endRef} />
      </div>

      <form className="composer" onSubmit={submit}>
        <label htmlFor="question" className="sr-only">
          Ask a question
        </label>
        <textarea
          id="question"
          rows={2}
          value={draft}
          placeholder={`Ask as ${personName}`}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          maxLength={2000}
        />
        <button type="submit" disabled={busy || !draft.trim()}>
          Ask
        </button>
      </form>
    </section>
  );
}
