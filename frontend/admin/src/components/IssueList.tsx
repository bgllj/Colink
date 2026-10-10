import type { IssueOut } from "../api/types";

function severityClass(severity: string): string {
  if (severity === "warning") return "severity-warning";
  if (severity === "info") return "severity-info";
  return "severity-error";
}

export function IssueList({ issues }: { issues: IssueOut[] }) {
  if (issues.length === 0) {
    return <p className="muted">无</p>;
  }
  return (
    <ul className="issue-list">
      {issues.map((issue, index) => (
        <li key={`${issue.code}-${index}`}>
          <span className={severityClass(issue.severity)}>[{issue.severity}]</span>{" "}
          <span className="mono">{issue.code}</span> {issue.message}
          {issue.sheet || issue.coordinate ? (
            <span className="muted">
              {" "}
              （{issue.sheet ?? "?"}
              {issue.coordinate ? ` ${issue.coordinate}` : ""}
              {issue.line_index != null ? ` 行${issue.line_index}` : ""}）
            </span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
