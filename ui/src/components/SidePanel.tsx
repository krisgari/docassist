import type { Message, Selection } from "../types";

const GUARDRAIL_LABEL: Record<string, string> = {
  prompt_injection_flagged: "Instructions found inside a document were treated as data",
  acl_late_check_drop: "Removed by the live permission check",
  sensitive_data_redacted: "Sensitive values redacted",
  grounding_revision: "Answer revised after the grounding check",
  unsupported_statements_removed: "Unsupported statements removed",
  uncited_statements: "Statements without a citation",
  invalid_citation_removed: "Invalid citation removed",
  extractive_fallback: "Fell back to quoting sources directly",
  model_failover: "Switched to the fallback model",
  model_unavailable: "Model unavailable",
  context_budget: "Context budget reached",
  truncated: "Answer hit the length limit",
};

const TOOL_LABEL: Record<string, string> = {
  search_docs: "Searched",
  fetch_doc: "Opened document",
  start_summary_job: "Started background job",
};

export function SidePanel({
  message,
  selection,
  onSelect,
}: {
  message: Message | undefined;
  selection: Selection;
  onSelect: (s: Selection) => void;
}) {
  if (!message?.result || !selection) {
    return (
      <aside className="side side-empty">
        <p>Select a citation number or “How this was answered” to see the sources and every step behind an answer.</p>
      </aside>
    );
  }
  const r = message.result;
  const tab = selection.tab;

  return (
    <aside className="side" aria-label="Answer details">
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === "sources"} onClick={() => onSelect({ ...selection, tab: "sources" })}>
          Sources ({r.citations.length})
        </button>
        <button role="tab" aria-selected={tab === "how"} onClick={() => onSelect({ ...selection, tab: "how" })}>
          How this was answered
        </button>
      </div>

      {tab === "sources" && (
        <ol className="sources">
          {r.citations.length === 0 && <li className="quiet">This answer cites no sources.</li>}
          {r.citations.map((c) => (
            <li
              key={c.n}
              id={`src-${message.id}-${c.n}`}
              className={"source" + (selection.citation === c.n ? " is-active" : "")}
              ref={(el) => {
                if (el && selection.citation === c.n) el.scrollIntoView({ block: "nearest" });
              }}
            >
              <div className="source-head">
                <span className="source-n">{c.n}</span>
                <div>
                  <p className="source-title">{c.title}</p>
                  <p className="source-path">{c.section.split(" > ").slice(1).join(" › ") || c.section}</p>
                </div>
              </div>
              <blockquote className="source-snippet">{c.snippet}</blockquote>
              <p className="source-meta">
                {c.sourceSystem} · updated {c.modified ?? "unknown"}
                {c.status === "superseded" && <span className="badge badge-warn">Superseded</span>}
              </p>
            </li>
          ))}
        </ol>
      )}

      {tab === "how" && (
        <div className="how">
          <h3>Steps</h3>
          <ol className="steps">
            {r.trace.length === 0 && <li className="quiet">Answered without calling any tools.</li>}
            {r.trace.map((t, i) => (
              <li key={i} className={t.error ? "step-error" : ""}>
                <span className="step-tool">{TOOL_LABEL[t.tool] ?? t.tool}</span>
                {typeof t.input.query === "string" && <span className="step-query">“{t.input.query}”</span>}
                {typeof t.input.doc_id === "string" && <span className="step-query">{t.input.doc_id}</span>}
                {typeof t.input.topic === "string" && <span className="step-query">“{t.input.topic}”</span>}
                <span className="step-summary">{t.summary.includes(" ms") ? t.summary : `${t.summary}, ${t.ms} ms`}</span>
              </li>
            ))}
          </ol>

          <h3>Safeguards</h3>
          <ul className="guards">
            <li>Search was limited to documents this person can open, and every source was re-checked against live permissions.</li>
            {r.droppedByPermissionCheck.length > 0 && (
              <li className="guard-hit">{r.droppedByPermissionCheck.length} document(s) removed by the live permission check</li>
            )}
            {r.guardrails.map((g, i) => (
              <li key={i} className="guard-hit">
                {GUARDRAIL_LABEL[g.type] ?? g.type}
                <span className="quiet"> — {g.detail}</span>
              </li>
            ))}
          </ul>

          <h3>Cost and time</h3>
          <table className="usage">
            <thead>
              <tr>
                <th scope="col">Model</th>
                <th scope="col">Input tokens</th>
                <th scope="col">Cached</th>
                <th scope="col">Output</th>
                <th scope="col">Cost (USD)</th>
              </tr>
            </thead>
            <tbody>
              {r.usage.map((u) => (
                <tr key={u.model}>
                  <td>{u.model}</td>
                  <td>{u.inputTokens.toLocaleString()}</td>
                  <td>{u.cacheReadTokens.toLocaleString()}</td>
                  <td>{u.outputTokens.toLocaleString()}</td>
                  <td>{u.costUsd.toFixed(5)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="quiet">
            {r.passagesConsidered} passages ranked, {(r.latencyMs / 1000).toFixed(2)} s end to end. Audit id{" "}
            {r.auditId?.slice(0, 8) ?? "—"}.
          </p>
        </div>
      )}
    </aside>
  );
}
