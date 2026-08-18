import { act, cleanup, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home, { metadata } from "./page";
import { LANDING_BRAND_INTRO_SEEN_KEY } from "./LandingBrandIntro";
import motionStyles from "./Landing.motion.module.css";

const redirect = vi.fn();

vi.mock("next/navigation", () => ({ redirect }));

function rect(left: number, top: number, width: number, height: number) {
  return {
    left,
    top,
    width,
    height,
    right: left + width,
    bottom: top + height,
    x: left,
    y: top,
    toJSON: () => ({}),
  } as DOMRect;
}

function mockBrandGeometry() {
  return vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function () {
    if (this.getAttribute("data-tieat-wordmark") === "header") {
      return rect(8, 20, 96, 34);
    }

    if (this.getAttribute("data-tieat-wordmark") === "hero") {
      return rect(320, 260, 320, 112);
    }

    return rect(0, 0, 0, 0);
  });
}

function expectWordmarkGeometry(mark: Element) {
  expect(mark).toHaveAttribute("viewBox", "0 0 300 108");
  expect(mark).toHaveAttribute("shape-rendering", "geometricPrecision");
  expect(mark).toHaveAttribute("data-wordmark-spacing", "6");
  expect(mark).toHaveAttribute("data-wordmark-join", "optical");
  expect(mark).toHaveAttribute("data-wordmark-kerning", "optical");
  expect(mark).toHaveAttribute("data-wordmark-single-e", "true");
  expect(mark.querySelectorAll("[data-wordmark-layer='depth']")).toHaveLength(3);
  expect(mark.querySelectorAll("[data-wordmark-layer='face']")).toHaveLength(3);
  expect(mark.querySelectorAll("[data-wordmark-glyph='e']")).toHaveLength(1);
  expect(mark.querySelectorAll("[data-wordmark-fork-i]")).toHaveLength(1);
  expect(mark.querySelector("[data-wordmark-fork-i]")?.getAttribute("d")).toBe(
    "M80 14H84V29H87V14H91V29H94V14H98V31C98 36 95 39 94 40V94H84V40C83 39 80 36 80 31Z",
  );
  expect(mark.querySelector("[data-wordmark-segment='ti'] [data-wordmark-layer='depth']"))?.toHaveAttribute(
    "transform",
    "translate(1.5 2.25)",
  );
  expect(mark.querySelector("[data-wordmark-segment='at'] [data-wordmark-layer='depth']"))?.toHaveAttribute(
    "transform",
    "translate(1.5 2.25)",
  );
  expect(mark.querySelector("[data-wordmark-segment='e'] [data-wordmark-layer='depth']"))?.toHaveAttribute(
    "transform",
    "translate(1.5 2.25)",
  );
  expect(mark.querySelector("[data-wordmark-segment='at'] [data-wordmark-layer='face'] > path:first-child")).toHaveAttribute(
    "d",
    "M202 14H214L242 94H229L222 75H194L187 94H170L202 14Z M208 34C201.5 34 197 38.6 197 44.5C197 49.8 200.4 53.3 205 55V68C205 69.7 206.3 71 208 71C209.7 71 211 69.7 211 68V55C215.6 53.3 219 49.8 219 44.5C219 38.6 214.5 34 208 34Z",
  );
  expect(mark.querySelectorAll("[data-wordmark-spoon-counter]")).toHaveLength(1);
  expect(mark.querySelector("[data-wordmark-segment='e'] [data-wordmark-layer='face'] > path")).toHaveAttribute(
    "d",
    "M104 14H162V26H118V46H164V58H118V82H162V94H104Z",
  );
  expect(mark.querySelector("[data-wordmark-segment='at'] [data-wordmark-layer='face'] > path:last-child")).toHaveAttribute(
    "d",
    "M226 14H282V26H260V94H248V26H226Z",
  );
  expect(mark.querySelectorAll("[data-wordmark-cue], [data-wordmark-fork-rhythm], [data-wordmark-rounded-counter], [data-tieat-rope], [data-tieat-infinity], [data-tieat-box]")).toHaveLength(0);
}

describe("public landing page", () => {
  beforeEach(() => {
    sessionStorage.clear();
    document.querySelector("[data-tieat-landing-root]")?.removeAttribute("data-tieat-landing-mode");
    redirect.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.querySelector("[data-tieat-landing-root]")?.removeAttribute("data-tieat-landing-mode");
  });

  it("starts from E without a full-word flash and moves the real hero wordmark to the header once", () => {
    vi.useFakeTimers();
    mockBrandGeometry();

    render(
      <StrictMode>
        <Home />
      </StrictMode>,
    );
    act(() => {});

    const root = document.querySelector("[data-tieat-landing-root]");
    const preflight = root?.firstElementChild;
    const intro = document.querySelector("[data-brand-intro]");
    const introMark = document.querySelector("[data-brand-intro-mark]");
    const headerMark = document.querySelector("[data-tieat-wordmark='header']");
    const heroMark = document.querySelector("[data-tieat-wordmark='hero']");
    const heroBrand = document.querySelector("[data-hero-brand]");

    expect(preflight?.tagName).toBe("SCRIPT");
    expect(preflight).toHaveAttribute("data-tieat-preflight");
    expect(preflight?.textContent).toContain(LANDING_BRAND_INTRO_SEEN_KEY);
    expect(preflight?.textContent).toContain("document.currentScript?.parentElement");
    expect(preflight?.textContent).not.toContain("document.documentElement");
    expect(root?.querySelector("header")).toBeTruthy();
    expect(root?.firstElementChild).toBe(preflight);
    expect(root).toHaveAttribute("data-tieat-landing-mode", "active");
    expect(sessionStorage.getItem(LANDING_BRAND_INTRO_SEEN_KEY)).toBeNull();
    expect(intro).toHaveAttribute("aria-hidden", "true");
    expect(intro).toHaveAttribute("data-brand-intro-source", "hero-wordmark");
    expect(intro).toHaveAttribute("data-brand-intro-phase", "e-core");
    expect(introMark).toHaveStyle({ left: "320px", top: "260px", width: "320px", height: "112px" });
    expect(introMark).toHaveStyle({ "--brand-target-x": "-424px", "--brand-target-y": "-279px", "--brand-target-scale": "0.3" });
    expect(headerMark).not.toHaveAttribute("data-brand-intro-hidden");
    expect(heroMark).not.toHaveAttribute("data-brand-intro-hidden");
    expect(heroBrand).not.toHaveAttribute("aria-hidden");
    expect(intro?.querySelector("[data-wordmark-segment='e']"))?.not.toHaveClass(motionStyles.brandIntroSegmentHidden);
    expect(intro?.querySelector("[data-wordmark-segment='at']"))?.toHaveClass(motionStyles.brandIntroSegmentHidden);
    expect(intro?.querySelector("[data-wordmark-segment='ti']"))?.toHaveClass(motionStyles.brandIntroSegmentHidden);
    expect(intro?.querySelector("a, button, input, [tabindex], [role], [aria-live]")).toBeNull();
    expectWordmarkGeometry(intro!.querySelector("[data-tieat-wordmark='intro']")!);

    act(() => {
      vi.advanceTimersByTime(900);
    });
    expect(document.querySelector("[data-brand-intro]")).toHaveAttribute("data-brand-intro-phase", "at");
    expect(intro?.querySelector("[data-wordmark-segment='at']")).toHaveClass(motionStyles.brandIntroSegmentAtReveal);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(document.querySelector("[data-brand-intro]")).toHaveAttribute("data-brand-intro-phase", "ti");
    expect(intro?.querySelector("[data-wordmark-segment='ti']")).toHaveClass(motionStyles.brandIntroSegmentTiReveal);

    act(() => {
      vi.advanceTimersByTime(800);
    });
    expect(document.querySelector("[data-brand-intro]")).toHaveAttribute("data-brand-intro-phase", "hold");

    act(() => {
      vi.advanceTimersByTime(750);
    });
    expect(document.querySelector("[data-brand-intro]")).toHaveAttribute("data-brand-intro-phase", "move");
    expect(intro?.querySelector("[data-wordmark-segment='ti']")).toHaveClass(motionStyles.brandIntroSegmentVisible);
    expect(intro?.querySelector("[data-wordmark-segment='at']")).toHaveClass(motionStyles.brandIntroSegmentVisible);

    act(() => {
      vi.advanceTimersByTime(1200);
    });
    expect(document.querySelector("[data-brand-intro]")).toHaveAttribute("data-brand-intro-phase", "settle");
    expect(root).toHaveAttribute("data-tieat-landing-mode", "settle");
    expect(heroBrand).toHaveAttribute("aria-hidden", "true");
    expect(sessionStorage.getItem(LANDING_BRAND_INTRO_SEEN_KEY)).toBeNull();

    act(() => {
      vi.advanceTimersByTime(180);
    });
    expect(document.querySelector("[data-brand-intro]")).toBeNull();
    expect(root).toHaveAttribute("data-tieat-landing-mode", "done");
    expect(sessionStorage.getItem(LANDING_BRAND_INTRO_SEEN_KEY)).toBe("1");
    expect(heroBrand).toHaveAttribute("aria-hidden", "true");
    expect(headerMark).not.toHaveAttribute("data-brand-intro-hidden");

    cleanup();
    render(
      <StrictMode>
        <Home />
      </StrictMode>,
    );
    expect(document.querySelector("[data-tieat-landing-root]")).toHaveAttribute("data-tieat-landing-mode", "skip");
    expect(document.querySelector("[data-brand-intro]")).toBeNull();
  });

  it("skips the intro for the same session and for reduced motion", () => {
    sessionStorage.setItem(LANDING_BRAND_INTRO_SEEN_KEY, "1");
    render(<Home />);

    expect(document.querySelector("[data-tieat-landing-root]")).toHaveAttribute("data-tieat-landing-mode", "skip");
    expect(document.querySelector("[data-brand-intro]")).toBeNull();
    expect(screen.getAllByRole("link", { name: "TIEAT 홈" })[0]).toBeVisible();
    expect(document.querySelector("[data-hero-brand]")).toHaveAttribute("aria-hidden", "true");

    cleanup();
    sessionStorage.clear();
    document.querySelector("[data-tieat-landing-root]")?.removeAttribute("data-tieat-landing-mode");
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    render(<Home />);

    expect(document.querySelector("[data-tieat-landing-root]")).toHaveAttribute("data-tieat-landing-mode", "skip");
    expect(document.querySelector("[data-brand-intro]")).toBeNull();
    expect(sessionStorage.getItem(LANDING_BRAND_INTRO_SEEN_KEY)).toBe("1");
    expect(document.querySelector("[data-hero-brand]")).toHaveAttribute("aria-hidden", "true");
  });

  it("keeps the approved landing copy, flow order, actions, preview, footer, and shared mark contract", () => {
    sessionStorage.setItem(LANDING_BRAND_INTRO_SEEN_KEY, "1");
    render(<Home />);

    expect(metadata).toMatchObject({
      title: "TIEAT | 식대 장부를 한 큐에",
      description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
      openGraph: {
        title: "TIEAT | 식대 장부를 한 큐에",
        description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
        type: "website",
        images: [{ url: "/og/tieat-og.png" }],
      },
      twitter: {
        card: "summary_large_image",
        title: "TIEAT | 식대 장부를 한 큐에",
        description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
        images: ["/og/tieat-og.png"],
      },
    });

    const [wordmarkLink] = screen.getAllByRole("link", { name: "TIEAT 홈" });
    const headerMark = wordmarkLink.querySelector("[data-tieat-wordmark='header']");

    expect(wordmarkLink).toBeVisible();
    expect(wordmarkLink).toHaveAccessibleName("TIEAT 홈");
    expect(headerMark).toBeVisible();
    expectWordmarkGeometry(headerMark!);
    expect(wordmarkLink.querySelector("img")).toBeNull();

    const heroBrand = document.querySelector("[data-hero-brand]");
    const heroWordmark = heroBrand?.querySelector("[data-tieat-wordmark='hero']");

    expect(heroBrand).toBeInTheDocument();
    expect(heroBrand).toHaveAttribute("role", "img");
    expect(heroBrand).toHaveAttribute("aria-label", "TIEAT");
    expect(heroBrand).toHaveAttribute("aria-hidden", "true");
    expectWordmarkGeometry(heroWordmark!);
    const heroTitle = screen.getByRole("heading", { name: /기록은 가볍게/ });
    expect(heroTitle).toBeVisible();
    expect(heroTitle.className).toContain("max-w-[32rem]");
    expect(heroTitle.className).toContain("text-[clamp(2.5rem,6.5vw,4.5rem)]");
    expect(heroTitle.className).toContain("leading-[1.1]");
    expect(heroTitle.className).toContain("tracking-[0.002em]");
    expect(screen.getByText("자동으로 기록이 쌓이는 장부")).toBeVisible();
    expect(screen.getByText("QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에.")).toBeVisible();
    expect(screen.queryByText("TIE, 하나로 묶다.")).toBeNull();
    expect(document.querySelector("[data-tie-graphic-section]")).toBeNull();

    const heroSection = document.querySelector("[data-hero-section]");
    const flowSection = document.getElementById("flow");

    expect(heroSection?.nextElementSibling).toBe(flowSection);
    expect(screen.getAllByRole("link", { name: "서비스 시작하기" })[0]).toHaveAttribute("href", "/store/meal-usages/months");
    expect(screen.getAllByRole("link", { name: "문의하기" })[0]).toHaveAttribute("href", "#contact");
    expect(screen.getByText("QR로 입력")).toBeVisible();
    expect(screen.getByText("매장에서 확인")).toBeVisible();
    expect(screen.getByText("장부에서 결제 확인")).toBeVisible();
    expect(screen.getByText("도입 문의 채널을 준비 중입니다.")).toBeVisible();

    const footer = screen.getByRole("contentinfo");
    const footerBrand = within(footer).getByRole("link", { name: "TIEAT 홈" });
    const footerNav = within(footer).getByRole("navigation", { name: "하단 메뉴" });

    expect(footerBrand).toHaveAttribute("href", "/");
    expect(footerBrand.querySelector("img")).toHaveAttribute("src", "/icon.svg");
    expect(footerBrand.querySelector("img")).toHaveAttribute("alt", "");
    expect(within(footer).getByText("TIEAT")).toBeVisible();
    expect(within(footer).getByText("QR 입력부터 매장 확인과 장부까지.")).toBeVisible();
    expect(within(footerNav).getByRole("link", { name: "서비스 시작하기" })).toHaveAttribute("href", "/store/meal-usages/months");
    expect(within(footerNav).getByRole("link", { name: "사용 방법" })).toHaveAttribute("href", "#flow");
    expect(within(footerNav).getByRole("link", { name: "문의하기" })).toHaveAttribute("href", "#contact");
    expect(within(footer).getByText("© 2026 TIEAT. All rights reserved.")).toBeVisible();
    expect(within(footer).getByText("현장 기록을 더 단순하게")).toBeVisible();

    const flow = screen.getByRole("list", { name: "사용 흐름 단계" });
    const steps = screen.getAllByRole("listitem").filter((item) => item.hasAttribute("data-flow-step-index"));
    const desktopPreview = document.querySelector("[data-product-flow-preview-placement='desktop'] [data-product-flow-preview]");
    const mobilePreviews = Array.from(document.querySelectorAll("[data-product-flow-preview-placement='mobile'] [data-product-flow-preview]"));

    expect(flow).toBeVisible();
    expect(steps).toHaveLength(3);
    expect(steps[0]).toHaveAttribute("aria-current", "step");
    expect(steps[0]).toHaveTextContent("QR로 입력");
    expect(steps[1]).toHaveTextContent("매장에서 확인");
    expect(steps[2]).toHaveTextContent("장부에서 결제 확인");
    expect(desktopPreview).toHaveAttribute("data-preview-step", "01");
    expect(desktopPreview).toHaveTextContent("화면 예시");
    expect(desktopPreview).toHaveTextContent("강남점 식대 요청");
    expect(desktopPreview).toHaveTextContent("협력사 검색");
    expect(desktopPreview).toHaveTextContent("협력사 A");
    expect(desktopPreview).toHaveTextContent("홍길동");
    expect(desktopPreview).toHaveTextContent("₩12,000");
    expect(desktopPreview).toHaveTextContent("요청 보내기");
    expect(mobilePreviews).toHaveLength(3);
    expect(mobilePreviews.map((preview) => preview.getAttribute("data-preview-step"))).toEqual(["01", "02", "03"]);
    expect(mobilePreviews[0]).toHaveTextContent("모바일 QR 입력");
    expect(mobilePreviews[0]).toHaveTextContent("협력사 검색");
    expect(mobilePreviews[1]).toHaveTextContent("매장 확인");
    expect(mobilePreviews[1]).toHaveTextContent("확인 대기");
    expect(mobilePreviews[1]).toHaveTextContent("거절");
    expect(mobilePreviews[1]).toHaveTextContent("확정");
    expect(mobilePreviews[2]).toHaveTextContent("월별 장부");
    expect(mobilePreviews[2]).toHaveTextContent("조회 기간 합계");
    expect(mobilePreviews[2]).toHaveTextContent("결제 전");
    expect(mobilePreviews[2]).toHaveTextContent("결제 완료");
    expect(mobilePreviews[2]).toHaveTextContent("결제 완료(선불)");
    expect(screen.queryByText("PENDING")).toBeNull();

    const previews = [desktopPreview, ...mobilePreviews];

    previews.forEach((preview) => {
      expect(preview).toHaveAttribute("aria-hidden", "true");
      expect(preview?.querySelector("button, input, a, [tabindex], [role]")).toBeNull();
    });
    expect(steps[0].querySelector("[data-product-flow-preview-placement='mobile']")).toBeTruthy();
    expect(steps[1].querySelector("[data-product-flow-preview-placement='mobile']")).toBeTruthy();
    expect(steps[2].querySelector("[data-product-flow-preview-placement='mobile']")).toBeTruthy();
    expect(redirect).not.toHaveBeenCalled();
  });
});
