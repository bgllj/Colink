/** Pure row-selection helpers shared by ImportPreviewPage and tests. */
export function toggleRow(selected: ReadonlySet<string>, rowId: string): Set<string> {
  const next = new Set(selected);
  if (next.has(rowId)) next.delete(rowId);
  else next.add(rowId);
  return next;
}

export function toggleAllVisible(
  selected: ReadonlySet<string>,
  visibleIds: string[],
  allVisibleSelected: boolean,
): Set<string> {
  const next = new Set(selected);
  if (allVisibleSelected) {
    for (const id of visibleIds) next.delete(id);
  } else {
    for (const id of visibleIds) next.add(id);
  }
  return next;
}

export function allVisibleSelected(selected: ReadonlySet<string>, visibleIds: string[]): boolean {
  return visibleIds.length > 0 && visibleIds.every((id) => selected.has(id));
}
