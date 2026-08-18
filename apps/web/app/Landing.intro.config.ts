export const LANDING_BRAND_INTRO_SEEN_KEY = "tieat.landingBrandIntro.seen.v7";

export const LANDING_BRAND_INTRO_PREFLIGHT_SCRIPT = `(() => {
  const key = "${LANDING_BRAND_INTRO_SEEN_KEY}";
  const root = document.currentScript?.parentElement;
  if (!root) return;
  let mode = "play";

  try {
    const seen = window.sessionStorage.getItem(key) === "1";
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches === true;
    if (seen || reduced) mode = "skip";
  } catch {
    mode = "play";
  }

  root.dataset.tieatLandingMode = mode;
})();`;
