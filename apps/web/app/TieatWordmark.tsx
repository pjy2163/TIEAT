import { landingStyles } from "./Landing.styles";

type TieatWordmarkProps = {
  atClassName?: string;
  className?: string;
  eClassName?: string;
  markId?: "header" | "hero" | "intro";
  tiClassName?: string;
  variant?: "header" | "hero" | "intro";
};

const T_PATH = "M18 14H74V26H52V94H40V26H18Z";
const FORK_I_PATH = "M80 14H84V29H87V14H91V29H94V14H98V31C98 36 95 39 94 40V94H84V40C83 39 80 36 80 31Z";
const E_PATH = "M104 14H162V26H118V46H164V58H118V82H162V94H104Z";
const A_PATH = "M202 14H214L242 94H229L222 75H194L187 94H170L202 14Z M208 34C201.5 34 197 38.6 197 44.5C197 49.8 200.4 53.3 205 55V68C205 69.7 206.3 71 208 71C209.7 71 211 69.7 211 68V55C215.6 53.3 219 49.8 219 44.5C219 38.6 214.5 34 208 34Z";
const FINAL_T_PATH = "M226 14H282V26H260V94H248V26H226Z";

export function TieatWordmark({
  atClassName = "",
  className = "",
  eClassName = "",
  markId,
  tiClassName = "",
  variant,
}: TieatWordmarkProps) {
  const isHeader = variant === "header";
  const darkDepthClass = isHeader ? landingStyles.tieatWordmarkHeaderDepth : landingStyles.tieatWordmarkDepth;
  const eDepthClass = isHeader ? landingStyles.tieatWordmarkHeaderDepthE : landingStyles.tieatWordmarkDepthE;

  return (
    <svg
      className={`${landingStyles.tieatWordmark} ${className}`}
      viewBox="0 0 300 108"
      shapeRendering="geometricPrecision"
      aria-hidden="true"
      focusable="false"
      data-tieat-wordmark={markId}
      data-wordmark="TIEAT"
      data-wordmark-variant={variant}
      data-wordmark-contiguous="true"
      data-wordmark-join="optical"
      data-wordmark-spacing="6"
      data-wordmark-kerning="optical"
      data-wordmark-single-e="true"
    >
      <g className={`${landingStyles.tieatWordmarkSide} ${tiClassName}`} data-wordmark-segment="ti">
        <g className={darkDepthClass} data-wordmark-layer="depth" transform="translate(1.5 2.25)">
          <path d={T_PATH} />
          <path d={FORK_I_PATH} fillRule="evenodd" />
        </g>
        <g className={landingStyles.tieatWordmarkFace} data-wordmark-layer="face">
          <path d={T_PATH} />
          <path d={FORK_I_PATH} fillRule="evenodd" data-wordmark-fork-i="true" />
        </g>
      </g>

      <g className={`${landingStyles.tieatWordmarkSide} ${atClassName}`} data-wordmark-segment="at">
        <g className={darkDepthClass} data-wordmark-layer="depth" transform="translate(1.5 2.25)">
          <path d={A_PATH} fillRule="evenodd" clipRule="evenodd" />
          <path d={FINAL_T_PATH} />
        </g>
        <g className={landingStyles.tieatWordmarkFace} data-wordmark-layer="face">
          <path d={A_PATH} fillRule="evenodd" clipRule="evenodd" data-wordmark-spoon-counter="true" />
          <path d={FINAL_T_PATH} />
        </g>
      </g>

      <g
        className={`${landingStyles.tieatWordmarkCore} ${landingStyles.tieatWordmarkE} ${eClassName}`}
        data-wordmark-segment="e"
        data-wordmark-core="true"
      >
        <g className={eDepthClass} data-wordmark-layer="depth" transform="translate(1.5 2.25)">
          <path d={E_PATH} />
        </g>
        <g className={landingStyles.tieatWordmarkEFace} data-wordmark-layer="face">
          <path d={E_PATH} data-wordmark-glyph="e" />
        </g>
      </g>
    </svg>
  );
}
