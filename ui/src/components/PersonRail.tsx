import type { Person } from "../api";

const GROUP_LABEL: Record<string, string> = {
  employees: "All employees",
  engineering: "Engineering",
  oncall: "On-call",
  sales: "Sales",
  hr: "HR",
  execs: "Executive team",
  legal: "Legal",
};

/** The memorable part of the demo: who you are decides what the assistant can read. */
export function PersonRail({
  people,
  activeId,
  onSelect,
}: {
  people: Person[];
  activeId: string | null;
  onSelect: (userId: string) => void;
}) {
  return (
    <nav className="rail" aria-label="Choose who you are asking as">
      <h2 className="rail-title">Asking as</h2>
      <ul className="people">
        {people.map((p) => {
          const active = p.userId === activeId;
          const access = p.groups.filter((g) => g !== "oncall").map((g) => GROUP_LABEL[g] ?? g);
          return (
            <li key={p.userId}>
              <button
                type="button"
                className={"person" + (active ? " is-active" : "")}
                aria-pressed={active}
                ref={(el) => {
                  if (el && active) el.scrollIntoView({ block: "nearest", inline: "nearest" });
                }}
                onClick={() => onSelect(p.userId)}
              >
                <span className="person-name">{p.name}</span>
                <span className="person-title">{p.title}</span>
                <span className="person-access">
                  {access.length ? `Can open: ${access.join(", ")}, public docs` : "Can open: public docs only"}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
