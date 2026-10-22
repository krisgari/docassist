import { useEffect, useState } from "react";
import { api, type Insights } from "../api";

/** Operator view: what an FDE checks every morning — quality, cost, latency, freshness, recent traces. */
export function Ops({ token }: { token: string }) {
  const [data, setData] = useState<Insights | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () =>
      api
        .insights(token)
        .then((d) => alive && (setData(d), setError(null)))
        .catch((e) => alive && setError(e.message));
    load();
    const t = setInterval(load, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [token]);

  if (error) return <section className="ops"><p className="msg-error">{error}</p></section>;
  if (!data) return <section className="ops"><p className="quiet">Loading…</p></section>;
  const t = data.totals;

  return (
    <section className="ops" aria-label="Operations">
      <dl className="kpis">
        <div><dt>Questions, 7 days</dt><dd>{t.questions}</dd></div>
        <div><dt>Median latency</dt><dd>{(Number(t.p50_latency_ms) / 1000).toFixed(2)} s</dd></div>
        <div><dt>95th percentile latency</dt><dd>{(Number(t.p95_latency_ms) / 1000).toFixed(2)} s</dd></div>
        <div><dt>Average cost</dt><dd>${Number(t.avg_cost_usd).toFixed(4)}</dd></div>
        <div><dt>Not found</dt><dd>{t.not_found}</dd></div>
        <div><dt>Feedback</dt><dd>{data.feedback.up} useful, {data.feedback.down} wrong</dd></div>
      </dl>

      <h3>Recent questions</h3>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>When</th><th>Who</th><th>Question</th><th>Outcome</th><th>Sources</th><th>Tools</th><th>Latency</th><th>Cost</th><th>Feedback</th></tr>
          </thead>
          <tbody>
            {data.recent.map((r) => (
              <tr key={r.audit_id}>
                <td>{new Date(r.created_at).toLocaleTimeString()}</td>
                <td>{r.user_id}</td>
                <td className="q">{r.question}</td>
                <td><span className={"outcome outcome-" + r.outcome}>{r.outcome.replace("_", " ")}</span></td>
                <td>{r.cited_doc_ids.join(", ") || "—"}</td>
                <td>{r.tool_calls}</td>
                <td>{(r.latency_ms / 1000).toFixed(2)} s</td>
                <td>${Number(r.cost_usd).toFixed(4)}</td>
                <td>{r.rating === 1 ? "useful" : r.rating === -1 ? "wrong" : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3>Safeguards triggered</h3>
      <ul className="guard-counts">
        {data.guardrails.length === 0 && <li className="quiet">None yet.</li>}
        {data.guardrails.map((g) => (
          <li key={g.type}><span>{g.type.replace(/_/g, " ")}</span><strong>{g.n}</strong></li>
        ))}
      </ul>

      <h3>Index freshness</h3>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Document</th><th>Status</th><th>Sensitivity</th><th>Chunks</th><th>Source updated</th><th>Indexed</th><th>Flags</th></tr>
          </thead>
          <tbody>
            {data.index.map((d) => (
              <tr key={d.doc_id}>
                <td>{d.title}</td>
                <td>{d.status}</td>
                <td>{d.sensitivity}</td>
                <td>{d.chunks}</td>
                <td>{d.modified_at?.slice(0, 10)}</td>
                <td>{new Date(d.indexed_at).toLocaleString()}</td>
                <td>{d.injection_flag ? "injection pattern" : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
