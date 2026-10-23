import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, streamChat, streamJob, type AgentResult, type Job, type Person, type ToolTrace, type Turn } from "./api";
import { JobsPanel } from "./components/JobsPanel";
import { Ops } from "./components/Ops";
import { PersonRail } from "./components/PersonRail";
import { SidePanel } from "./components/SidePanel";
import { Thread } from "./components/Thread";
import { STARTERS, type Message, type Selection } from "./types";

type Session = { token: string; person: Person };

let seq = 0;
const nextId = () => `m${++seq}`;
const newConversationId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`).replace(/[^A-Za-z0-9_-]/g, "");

export default function App() {
  const [people, setPeople] = useState<Person[]>([]);
  const [sessions, setSessions] = useState<Record<string, Session>>({});
  const [activeId, setActiveId] = useState<string | null>(null);
  const [threads, setThreads] = useState<Record<string, Message[]>>({});
  const [conversations, setConversations] = useState<Record<string, string>>({});
  const [jobs, setJobs] = useState<Record<string, Job[]>>({});
  const [busy, setBusy] = useState(false);
  const [selection, setSelection] = useState<Selection>(null);
  const [view, setView] = useState<"chat" | "ops">("chat");
  const [loadError, setLoadError] = useState<string | null>(null);
  const watching = useRef(new Set<string>());

  const session = activeId ? sessions[activeId] : undefined;
  const messages = (activeId && threads[activeId]) || [];
  const isAdmin = session?.person.groups.includes("execs") ?? false;

  useEffect(() => {
    api
      .people()
      .then((p) => {
        setPeople(p);
        if (p.length) select(p[0].userId, p);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "Can't reach the API. Is the stack running?"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const select = useCallback(
    async (userId: string, list: Person[] = people) => {
      setActiveId(userId);
      setSelection(null);
      if (!list.find((p) => p.userId === userId)?.groups.includes("execs")) setView("chat");
      if (!sessions[userId]) {
        const s = await api.signIn(userId);
        setSessions((prev) => ({ ...prev, [userId]: { token: s.token, person: s.user } }));
        const recent = await api.jobs(s.token).catch(() => []);
        setJobs((prev) => ({ ...prev, [userId]: recent }));
      }
    },
    [people, sessions],
  );

  const patch = (userId: string, id: string, fn: (m: Message) => Message) =>
    setThreads((prev) => ({ ...prev, [userId]: (prev[userId] ?? []).map((m) => (m.id === id ? fn(m) : m)) }));

  const upsertJob = (userId: string, job: Partial<Job> & { jobId: string }) =>
    setJobs((prev) => {
      const list = prev[userId] ?? [];
      const existing = list.find((j) => j.jobId === job.jobId);
      const merged = { ...(existing ?? ({} as Job)), ...job } as Job;
      return { ...prev, [userId]: existing ? list.map((j) => (j.jobId === job.jobId ? merged : j)) : [merged, ...list] };
    });

  const watchJob = (userId: string, token: string, jobId: string) => {
    if (watching.current.has(jobId)) return;
    watching.current.add(jobId);
    streamJob(token, jobId, (event, data) => {
      if (event === "snapshot") upsertJob(userId, data as Job);
      if (event === "progress") {
        const e = data as { status: Job["status"]; progress: number; note: string };
        upsertJob(userId, { jobId, status: e.status, ...(e.progress >= 0 ? { progress: e.progress } : {}), note: e.note });
        if (e.status === "succeeded" || e.status === "failed") api.job(token, jobId).then((j) => upsertJob(userId, j));
      }
    })
      .catch(() => undefined)
      .finally(() => {
        watching.current.delete(jobId);
        api.job(token, jobId).then((j) => upsertJob(userId, j)).catch(() => undefined);
      });
  };

  const ask = async (question: string) => {
    if (!session || !activeId || busy) return;
    const userId = activeId;
    const history: Turn[] = (threads[userId] ?? [])
      .filter((m) => !m.pending && (m.role === "user" || m.result))
      .map((m) => ({ role: m.role, content: m.role === "user" ? m.text : m.result!.answer }));
    const answerId = nextId();
    const conversationId = conversations[userId] ?? newConversationId();
    if (!conversations[userId]) setConversations((prev) => ({ ...prev, [userId]: conversationId }));
    setThreads((prev) => ({
      ...prev,
      [userId]: [
        ...(prev[userId] ?? []),
        { id: nextId(), role: "user", text: question, tools: [] },
        { id: answerId, role: "assistant", text: "", pending: true, status: "Starting", tools: [] },
      ],
    }));
    setBusy(true);
    try {
      await streamChat(session.token, question, history, conversationId, (event, data) => {
        if (event === "status") patch(userId, answerId, (m) => ({ ...m, status: (data as { message: string }).message }));
        if (event === "tool") patch(userId, answerId, (m) => ({ ...m, tools: [...m.tools, data as ToolTrace] }));
        if (event === "job") {
          const job = data as Job;
          upsertJob(userId, job);
          patch(userId, answerId, (m) => ({ ...m, job }));
          watchJob(userId, session.token, job.jobId);
        }
        if (event === "answer") {
          const result = data as AgentResult;
          patch(userId, answerId, (m) => ({ ...m, pending: false, result }));
          if (result.citations.length) setSelection({ messageId: answerId, citation: result.citations[0].n, tab: "sources" });
        }
        if (event === "error") patch(userId, answerId, (m) => ({ ...m, pending: false, error: (data as { message: string }).message }));
      });
    } catch (e) {
      const message =
        e instanceof ApiError && e.status === 429 ? e.message : e instanceof ApiError ? e.message : "Lost the connection to the assistant. Try again.";
      patch(userId, answerId, (m) => ({ ...m, pending: false, error: message }));
    } finally {
      patch(userId, answerId, (m) => (m.pending ? { ...m, pending: false, error: m.error ?? "The answer stream ended early. Try again." } : m));
      setBusy(false);
    }
  };

  const startJob = async (topic: string) => {
    if (!session || !activeId) return;
    try {
      const job = await api.summarize(session.token, topic);
      upsertJob(activeId, job);
      watchJob(activeId, session.token, job.jobId);
    } catch (e) {
      alert(e instanceof Error ? e.message : "Couldn't start the job");
    }
  };

  const newConversation = () => {
    if (!activeId || busy) return;
    setThreads((prev) => ({ ...prev, [activeId]: [] }));
    setConversations((prev) => ({ ...prev, [activeId]: newConversationId() }));
    setSelection(null);
  };

  const feedback = async (m: Message, rating: 1 | -1) => {
    if (!session || !activeId || !m.result?.auditId) return;
    patch(activeId, m.id, (x) => ({ ...x, feedback: rating }));
    await api.feedback(session.token, m.result.auditId, rating, rating === -1 ? "wrong" : undefined).catch(() => undefined);
  };

  const selectedMessage = selection ? messages.find((m) => m.id === selection.messageId) : undefined;

  if (loadError) {
    return (
      <main className="boot">
        <h1>Halcyon Docs Assistant</h1>
        <p className="msg-error">{loadError}</p>
        <p className="quiet">Start the stack with <code>docker compose up --build</code>, then reload.</p>
      </main>
    );
  }

  return (
    <div className="app">
      <header className="top">
        <h1>Halcyon Docs Assistant</h1>
        <p className="top-sub">Answers from internal documents, with sources, limited to what each person can open.</p>
        {isAdmin && (
          <div className="view-switch" role="tablist" aria-label="View">
            <button role="tab" aria-selected={view === "chat"} onClick={() => setView("chat")}>
              Ask
            </button>
            <button role="tab" aria-selected={view === "ops"} onClick={() => setView("ops")}>
              Operations
            </button>
          </div>
        )}
      </header>

      <div className="left">
        <PersonRail people={people} activeId={activeId} onSelect={(id) => select(id)} />
        {session && <JobsPanel jobs={jobs[activeId!] ?? []} onStart={startJob} busy={busy} />}
      </div>

      {view === "ops" && session ? (
        <main className="main-wide">
          <Ops token={session.token} />
        </main>
      ) : (
        <>
          <main className="main">
            {session ? (
              <Thread
                messages={messages}
                starters={STARTERS[activeId!] ?? []}
                busy={busy}
                selection={selection}
                onAsk={ask}
                onSelect={setSelection}
                onFeedback={feedback}
                onNewConversation={newConversation}
                personName={session.person.name.split(" ")[0]}
              />
            ) : (
              <p className="quiet pad">Signing in…</p>
            )}
          </main>
          <SidePanel message={selectedMessage} selection={selection} onSelect={setSelection} />
        </>
      )}
    </div>
  );
}
