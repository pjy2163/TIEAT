"use client";

import { useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { TieatWordmark } from "./TieatWordmark";
import { landingStyles } from "./Landing.styles";
import motionStyles from "./Landing.motion.module.css";
import { LANDING_BRAND_INTRO_SEEN_KEY } from "./Landing.intro.config";

export { LANDING_BRAND_INTRO_SEEN_KEY } from "./Landing.intro.config";

const AT_START_MS = 900;
const TI_START_MS = 1900;
const HOLD_START_MS = 2700;
const MOVE_START_MS = 3450;
const SETTLE_START_MS = 4650;
const INTRO_END_MS = 4830;

type IntroPhase = "pending" | "e-core" | "at" | "ti" | "hold" | "move" | "settle" | "done";
type LandingMode = "play" | "active" | "settle" | "done" | "skip";

type IntroTarget = {
  x: number;
  y: number;
  scale: number;
};

type IntroFrame = {
  left: number;
  top: number;
  width: number;
  height: number;
};

function prefersReducedMotion() {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function hasSeenIntro() {
  try {
    return window.sessionStorage.getItem(LANDING_BRAND_INTRO_SEEN_KEY) === "1";
  } catch {
    return false;
  }
}

function markIntroSeen() {
  try {
    window.sessionStorage.setItem(LANDING_BRAND_INTRO_SEEN_KEY, "1");
  } catch {
    // A blocked session store should not prevent the one-time visual from playing.
  }
}

function setLandingMode(mode: LandingMode) {
  document.querySelector<HTMLElement>("[data-tieat-landing-root]")?.setAttribute("data-tieat-landing-mode", mode);
}

function setHeroBrandCollapsed(collapsed: boolean) {
  const landingRoot = document.querySelector<HTMLElement>("[data-tieat-landing-root]");
  const heroBrand = landingRoot?.querySelector<HTMLElement>("[data-hero-brand]");

  if (!heroBrand) {
    return;
  }

  if (collapsed) {
    heroBrand.setAttribute("aria-hidden", "true");
  } else {
    heroBrand.removeAttribute("aria-hidden");
  }
}

function isValidFrame(frame: DOMRect) {
  return (
    frame.width > 0 &&
    frame.height > 0 &&
    Number.isFinite(frame.left) &&
    Number.isFinite(frame.top) &&
    Number.isFinite(frame.width) &&
    Number.isFinite(frame.height)
  );
}

export function LandingBrandIntro() {
  const terminalRef = useRef(false);
  const motionActiveRef = useRef(false);
  const timersRef = useRef<number[]>([]);
  const removeMotionListenersRef = useRef<(() => void) | null>(null);
  const [phase, setPhase] = useState<IntroPhase>("pending");
  const [target, setTarget] = useState<IntroTarget>({ x: 0, y: 0, scale: 1 });
  const [sourceFrame, setSourceFrame] = useState<IntroFrame | null>(null);

  /* eslint-disable react-hooks/set-state-in-effect -- Intro state is initialized from browser-only motion and session preferences after hydration. */
  useLayoutEffect(() => {
    const clearMotion = () => {
      timersRef.current.forEach((timer) => window.clearTimeout(timer));
      timersRef.current = [];
      removeMotionListenersRef.current?.();
      removeMotionListenersRef.current = null;
      motionActiveRef.current = false;
    };

    const landingRoot = document.querySelector<HTMLElement>("[data-tieat-landing-root]");
    const initialMode = landingRoot?.dataset.tieatLandingMode;
    if (initialMode === "skip" || prefersReducedMotion() || hasSeenIntro()) {
      terminalRef.current = true;
      setLandingMode("skip");
      setHeroBrandCollapsed(true);
      markIntroSeen();
      setPhase("done");
      return () => {
        clearMotion();
      };
    }

    setLandingMode("play");
    setHeroBrandCollapsed(false);

    const sourceMark = landingRoot?.querySelector<SVGElement>("[data-tieat-wordmark='hero']");
    const headerMark = landingRoot?.querySelector<SVGElement>("[data-tieat-wordmark='header']");

    if (!sourceMark || !headerMark) {
      terminalRef.current = true;
      setLandingMode("skip");
      setHeroBrandCollapsed(true);
      markIntroSeen();
      setPhase("done");
      return () => {
        clearMotion();
      };
    }

    const sourceRect = sourceMark.getBoundingClientRect();
    const headerRect = headerMark.getBoundingClientRect();

    if (!isValidFrame(sourceRect) || !isValidFrame(headerRect)) {
      terminalRef.current = true;
      setLandingMode("skip");
      setHeroBrandCollapsed(true);
      markIntroSeen();
      setPhase("done");
      return () => {
        clearMotion();
      };
    }

    const sourceCenterX = sourceRect.left + sourceRect.width / 2;
    const sourceCenterY = sourceRect.top + sourceRect.height / 2;
    const headerCenterX = headerRect.left + headerRect.width / 2;
    const headerCenterY = headerRect.top + headerRect.height / 2;
    const nextScale = Math.min(headerRect.width / sourceRect.width, headerRect.height / sourceRect.height);

    if (
      !Number.isFinite(sourceCenterX) ||
      !Number.isFinite(sourceCenterY) ||
      !Number.isFinite(headerCenterX) ||
      !Number.isFinite(headerCenterY) ||
      !Number.isFinite(nextScale) ||
      nextScale <= 0
    ) {
      terminalRef.current = true;
      setLandingMode("skip");
      setHeroBrandCollapsed(true);
      markIntroSeen();
      setPhase("done");
      return () => {
        clearMotion();
      };
    }

    setSourceFrame({
      left: sourceRect.left,
      top: sourceRect.top,
      width: sourceRect.width,
      height: sourceRect.height,
    });
    setTarget({
      x: headerCenterX - sourceCenterX,
      y: headerCenterY - sourceCenterY,
      scale: nextScale,
    });
    setPhase("e-core");
    setLandingMode("active");
    motionActiveRef.current = true;

    const cancelToStatic = () => {
      if (terminalRef.current) {
        return;
      }

      terminalRef.current = true;
      clearMotion();
      setLandingMode("skip");
      setHeroBrandCollapsed(true);
      markIntroSeen();
      setPhase("done");
    };

    window.addEventListener("resize", cancelToStatic, { passive: true });
    window.addEventListener("orientationchange", cancelToStatic, { passive: true });
    removeMotionListenersRef.current = () => {
      window.removeEventListener("resize", cancelToStatic);
      window.removeEventListener("orientationchange", cancelToStatic);
    };

    timersRef.current = [
      window.setTimeout(() => setPhase("at"), AT_START_MS),
      window.setTimeout(() => setPhase("ti"), TI_START_MS),
      window.setTimeout(() => setPhase("hold"), HOLD_START_MS),
      window.setTimeout(() => {
        setHeroBrandCollapsed(true);
        setPhase("move");
      }, MOVE_START_MS),
      window.setTimeout(() => {
        setLandingMode("settle");
        setHeroBrandCollapsed(true);
        setPhase("settle");
      }, SETTLE_START_MS),
      window.setTimeout(() => {
        terminalRef.current = true;
        clearMotion();
        setLandingMode("done");
        setHeroBrandCollapsed(true);
        markIntroSeen();
        setPhase("done");
      }, INTRO_END_MS),
    ];

    return () => {
      clearMotion();
      if (!terminalRef.current) {
        setLandingMode("play");
        setHeroBrandCollapsed(false);
      }
    };
  }, []);
  /* eslint-enable react-hooks/set-state-in-effect */

  if (phase === "pending" || phase === "done" || !sourceFrame) {
    return null;
  }

  const atClassName =
    phase === "e-core" || phase === "at"
      ? phase === "at"
        ? motionStyles.brandIntroSegmentAtReveal
        : motionStyles.brandIntroSegmentHidden
      : motionStyles.brandIntroSegmentVisible;
  const tiClassName =
    phase === "e-core" || phase === "at" || phase === "ti"
      ? phase === "ti"
        ? motionStyles.brandIntroSegmentTiReveal
        : motionStyles.brandIntroSegmentHidden
      : motionStyles.brandIntroSegmentVisible;
  const markPhaseClassName =
    phase === "move" ? motionStyles.brandIntroMove : phase === "settle" ? motionStyles.brandIntroSettle : motionStyles.brandIntroReady;
  const markStyle = {
    left: `${sourceFrame.left}px`,
    top: `${sourceFrame.top}px`,
    width: `${sourceFrame.width}px`,
    height: `${sourceFrame.height}px`,
    "--brand-target-x": `${target.x}px`,
    "--brand-target-y": `${target.y}px`,
    "--brand-target-scale": target.scale,
  } as CSSProperties;

  return (
    <div
      className={landingStyles.brandIntro}
      aria-hidden="true"
      data-brand-intro
      data-brand-intro-source="hero-wordmark"
      data-brand-intro-phase={phase}
    >
      <div
        className={`${landingStyles.brandIntroMark} ${motionStyles.brandIntroMark} ${markPhaseClassName}`}
        style={markStyle}
        data-brand-intro-mark
      >
        <TieatWordmark
          className={landingStyles.brandIntroMarkSvg}
          atClassName={atClassName}
          markId="intro"
          tiClassName={tiClassName}
          variant="intro"
        />
      </div>
    </div>
  );
}
