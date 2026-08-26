import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { LandingFlow } from "./LandingFlow";
import type { LandingFlowStep } from "./Landing.components";
import motionStyles from "./Landing.motion.module.css";

const steps: readonly LandingFlowStep[] = [
  { number: "01", title: "QR로 입력", description: "휴대폰으로 식대 사용 내용을 남깁니다." },
  { number: "02", title: "매장에서 확인", description: "태블릿에서 입력 내용을 확인합니다." },
  { number: "03", title: "장부에서 결제 확인", description: "확정된 내역과 금액을 확인합니다." },
];

type ObserverEntry = Pick<IntersectionObserverEntry, "target" | "isIntersecting" | "boundingClientRect">;

let triggerIntersection: (entries: ObserverEntry[]) => void = () => undefined;

class MockIntersectionObserver {
  constructor(callback: IntersectionObserverCallback) {
    triggerIntersection = (entries) => {
      callback(entries as IntersectionObserverEntry[], this as unknown as IntersectionObserver);
    };
  }

  observe() {}

  disconnect() {}
}

function entry(target: Element, top: number, isIntersecting = true): ObserverEntry {
  return {
    target,
    isIntersecting,
    boundingClientRect: { top } as DOMRect,
  };
}

function desktopPreview() {
  return document.querySelector("[data-product-flow-preview-placement='desktop'] [data-product-flow-preview]");
}

describe("LandingFlow motion contract", () => {
  beforeEach(() => {
    vi.stubGlobal("IntersectionObserver", MockIntersectionObserver);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    triggerIntersection = () => undefined;
  });

  it("maps the observer-selected card to one keyed desktop preview choreography", () => {
    render(<LandingFlow steps={steps} />);

    const cards = Array.from(document.querySelectorAll("[data-flow-step-index]"));
    expect(cards[0]).toHaveAttribute("aria-current", "step");
    expect(cards[0]).toHaveClass(motionStyles.flowCard);
    expect(desktopPreview()).toHaveAttribute("data-preview-step", "01");
    expect(desktopPreview()).not.toHaveClass(motionStyles.desktopPreviewPrimary);
    expect(desktopPreview()?.querySelector(`.${motionStyles.desktopPreviewSecondary}`)).toBeNull();

    act(() => {
      triggerIntersection([entry(cards[1], 120)]);
    });

    expect(cards[0]).not.toHaveAttribute("aria-current", "step");
    expect(cards[1]).toHaveAttribute("aria-current", "step");
    expect(desktopPreview()).toHaveAttribute("data-preview-step", "02");
    expect(document.querySelector("[data-product-flow-preview-placement='desktop'] [data-preview-step='01']")).toBeNull();
    expect(desktopPreview()).toHaveClass(motionStyles.desktopPreviewPrimary);
    expect(desktopPreview()?.querySelector(`.${motionStyles.desktopPreviewSecondary}`)).toBeTruthy();
  });

  it("keeps every preview decorative and mobile examples static", () => {
    render(<LandingFlow steps={steps} />);

    const previews = Array.from(document.querySelectorAll("[data-product-flow-preview]"));
    expect(previews).toHaveLength(4);

    previews.forEach((preview) => {
      expect(preview).toHaveAttribute("aria-hidden", "true");
      expect(preview).toHaveClass("pointer-events-none");
      expect(preview.querySelector("button, input, a, [tabindex], [role]")).toBeNull();
    });

    const mobilePreviews = Array.from(document.querySelectorAll("[data-product-flow-preview-placement='mobile'] [data-product-flow-preview]"));
    expect(mobilePreviews).toHaveLength(3);
    mobilePreviews.forEach((preview) => {
      expect(preview).not.toHaveClass(motionStyles.desktopPreviewPrimary);
      expect(preview.querySelector(`.${motionStyles.desktopPreviewSecondary}`)).toBeNull();
    });
  });

  it("declares bounded easing, durations, delay, and reduced-motion final-state rules", () => {
    const source = readFileSync("app/Landing.motion.module.css", "utf8");
    const ctaPressBlock = source.match(/\.ctaPress \{([^}]*)\}/)?.[1] ?? "";

    expect(ctaPressBlock).toContain("transition-property: transform;");
    expect(ctaPressBlock).not.toContain("background");
    expect(source).toContain("transition-duration: 150ms");
    expect(source).toContain("transition-duration: 240ms");
    expect(source).toContain("max-height: clamp(6.125rem, 20.16vw, 10.5rem);");
    expect(source).toContain(
      "transition: max-height 320ms cubic-bezier(0.2, 0, 0, 1), margin-bottom 320ms cubic-bezier(0.2, 0, 0, 1),",
    );
    expect(source).toContain("opacity 320ms cubic-bezier(0.2, 0, 0, 1), visibility 0s linear 320ms;");
    expect(source).toContain("max-height: 0 !important;");
    expect(source).not.toMatch(/\n\s+height: 0 !important;/);
    expect(source).not.toMatch(/\n\s+min-height: 0 !important;/);
    expect(source).toContain(":global([data-hero-brand]) {\n    transition: none !important;\n  }");
    expect(source).toContain("animation: desktopPreviewPrimary 280ms cubic-bezier(0.2, 0, 0, 1) both");
    expect(source).toContain("animation: desktopPreviewSecondary 220ms 56ms cubic-bezier(0.2, 0, 0, 1) both");
    expect(source).toContain("@media (prefers-reduced-motion: reduce)");
    expect(source).toContain("animation: none !important");
    expect(source).toContain("transition: none !important");
  });
});
