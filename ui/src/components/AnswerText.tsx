import { Fragment, type ReactNode } from "react";

/**
 * Minimal, safe markdown for answers: paragraphs, bullet and numbered lists, **bold**, and [n] citations as buttons.
 * Everything is rendered as React text nodes; model output is never injected as HTML.
 */
export function AnswerText({
  text,
  citable,
  active,
  onCite,
}: {
  text: string;
  citable: Set<number>;
  active?: number;
  onCite: (n: number) => void;
}) {
  const blocks: ReactNode[] = [];
  const lines = text.split("\n");
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
      continue;
    }
    const bullet = /^\s*[-*]\s+/;
    const numbered = /^\s*\d+[.)]\s+/;
    if (bullet.test(line) || numbered.test(line)) {
      const ordered = numbered.test(line);
      const items: string[] = [];
      while (i < lines.length && (ordered ? numbered : bullet).test(lines[i])) {
        items.push(lines[i].replace(ordered ? numbered : bullet, ""));
        i++;
      }
      const List = ordered ? "ol" : "ul";
      blocks.push(
        <List key={blocks.length}>
          {items.map((it, k) => (
            <li key={k}>{inline(it, citable, active, onCite)}</li>
          ))}
        </List>,
      );
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() && !bullet.test(lines[i]) && !numbered.test(lines[i])) {
      para.push(lines[i].replace(/^#+\s*/, ""));
      i++;
    }
    blocks.push(<p key={blocks.length}>{inline(para.join(" "), citable, active, onCite)}</p>);
  }
  return <div className="answer-text">{blocks}</div>;
}

function inline(s: string, citable: Set<number>, active: number | undefined, onCite: (n: number) => void): ReactNode {
  const parts = s.split(/(\*\*[^*]+\*\*|\[\d{1,2}\])/g);
  return parts.map((p, k) => {
    const cite = /^\[(\d{1,2})\]$/.exec(p);
    if (cite) {
      const n = Number(cite[1]);
      if (!citable.has(n)) return <Fragment key={k}>{p}</Fragment>;
      return (
        <button
          key={k}
          type="button"
          className={"cite" + (active === n ? " is-active" : "")}
          onClick={() => onCite(n)}
          aria-label={`Show source ${n}`}
        >
          {n}
        </button>
      );
    }
    if (p.startsWith("**") && p.endsWith("**")) return <strong key={k}>{p.slice(2, -2)}</strong>;
    return <Fragment key={k}>{p}</Fragment>;
  });
}
