import { describe, expect, it } from "vitest";
import { dynamic, metadata } from "./page";

describe("public QR page metadata", () => {
  it("disables indexing and keeps the route dynamic", () => {
    expect(dynamic).toBe("force-dynamic");
    expect(metadata).toMatchObject({
      referrer: "no-referrer",
      robots: {
        index: false,
        follow: false,
        noarchive: true,
        nocache: true,
      },
    });
  });
});
