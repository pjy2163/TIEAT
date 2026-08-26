import { landingStyles } from "./Landing.styles";
import motionStyles from "./Landing.motion.module.css";
import { TieatWordmark } from "./TieatWordmark";
import type { ReactNode, Ref } from "react";

type SectionHeadingProps = {
  id: string;
  eyebrow: string;
  title: string;
  description: string;
  className?: string;
};

export type LandingFlowStep = {
  number: string;
  title: string;
  description: string;
};

type FlowStepCardProps = LandingFlowStep & {
  active?: boolean;
  cardRef?: Ref<HTMLLIElement>;
  index?: number;
  preview?: ReactNode;
};

type BrandWordmarkProps = {
  variant?: "hero";
};

export function BrandWordmark({ variant = "hero" }: BrandWordmarkProps) {
  return <TieatWordmark className={landingStyles.heroBrandWordmark} markId="hero" variant={variant} />;
}

export function Wordmark() {
  return (
    <a className={landingStyles.wordmarkLink} href="/" aria-label="TIEAT 홈">
      <TieatWordmark className={landingStyles.wordmarkMark} markId="header" variant="header" />
    </a>
  );
}

export function SectionHeading({ id, eyebrow, title, description, className = "" }: SectionHeadingProps) {
  return (
    <div className={`${landingStyles.sectionIntro} ${className}`}>
      <p className={landingStyles.eyebrow}>{eyebrow}</p>
      <h2 id={id} className={landingStyles.sectionTitle}>{title}</h2>
      <p className={landingStyles.sectionDescription}>{description}</p>
    </div>
  );
}

export function FlowStepCard({ number, title, description, active = false, cardRef, index, preview }: FlowStepCardProps) {
  return (
    <li
      ref={cardRef}
      className={`${landingStyles.flowCard} ${motionStyles.flowCard} ${active ? landingStyles.flowCardActive : landingStyles.flowCardIdle}`}
      data-flow-step-index={index}
      aria-current={active ? "step" : undefined}
    >
      <span className={landingStyles.flowNumber}>{number}</span>
      {active ? <span className={landingStyles.flowCurrentLabel}>현재 단계</span> : null}
      <h3 className={landingStyles.flowTitle}>{title}</h3>
      <p className={landingStyles.flowDescription}>{description}</p>
      {preview}
    </li>
  );
}
