import { describe, expect, it } from "vitest";
import { allVisibleSelected, toggleAllVisible, toggleRow } from "./selection";

describe("row selection", () => {
  it("adds and removes a single row", () => {
    let selected = new Set<string>();
    selected = toggleRow(selected, "a");
    expect([...selected]).toEqual(["a"]);
    selected = toggleRow(selected, "a");
    expect([...selected]).toEqual([]);
  });

  it("selects all visible rows and unselects them", () => {
    const visible = ["a", "b", "c"];
    let selected = new Set<string>(["a", "z"]);
    selected = toggleAllVisible(selected, visible, false);
    expect(selected.has("a")).toBe(true);
    expect(selected.has("b")).toBe(true);
    expect(selected.has("c")).toBe(true);
    expect(selected.has("z")).toBe(true);

    expect(allVisibleSelected(selected, visible)).toBe(true);
    selected = toggleAllVisible(selected, visible, true);
    expect(selected.has("a")).toBe(false);
    expect(selected.has("b")).toBe(false);
    expect(selected.has("c")).toBe(false);
    expect(selected.has("z")).toBe(true);
  });

  it("reports not-all-selected when some visible rows are unchecked", () => {
    expect(allVisibleSelected(new Set(["a"]), ["a", "b"])).toBe(false);
    expect(allVisibleSelected(new Set(), [])).toBe(false);
  });
});
