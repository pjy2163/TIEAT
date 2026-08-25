"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { FlowStepCard, type LandingFlowStep } from "./Landing.components";
import { landingStyles } from "./Landing.styles";
import motionStyles from "./Landing.motion.module.css";

type LandingFlowProps = {
  steps: readonly LandingFlowStep[];
};

type ProductFlowPreviewProps = {
  step: LandingFlowStep;
  className?: string;
  screenClassName?: string;
};

function ProductFlowPreview({ step, className = "", screenClassName = "" }: ProductFlowPreviewProps) {
  let screen: ReactNode = null;
  const previewScreenClassName = `${landingStyles.productPreviewScreen} ${screenClassName}`.trim();

  switch (step.number) {
    case "01":
      screen = (
        <div className={previewScreenClassName}>
          <p className={landingStyles.productPreviewEyebrow}>모바일 QR 입력</p>
          <h3 className={landingStyles.productPreviewTitle}>강남점 식대 요청</h3>
          <div className={landingStyles.productPreviewRows}>
            <div className={landingStyles.productPreviewRow}>
              <span>협력사 검색</span>
              <strong className={landingStyles.productPreviewRowValue}>협력사 A</strong>
            </div>
            <div className={landingStyles.productPreviewRow}>
              <span>이름</span>
              <strong className={landingStyles.productPreviewRowValue}>홍길동</strong>
            </div>
          </div>
          <div className={landingStyles.productPreviewAmount}>
            <span>식대 금액</span>
            <strong className={landingStyles.productPreviewAmountValue}>₩12,000</strong>
          </div>
          <span className={landingStyles.productPreviewAction}>요청 보내기</span>
        </div>
      );
      break;
    case "02":
      screen = (
        <div className={previewScreenClassName}>
          <p className={landingStyles.productPreviewEyebrow}>매장 확인</p>
          <h3 className={landingStyles.productPreviewTitle}>확인 대기 거래</h3>
          <div className={landingStyles.productPreviewRows}>
            <div className={landingStyles.productPreviewRow}>
              <span>협력사</span>
              <strong className={landingStyles.productPreviewRowValue}>협력사 A</strong>
            </div>
            <div className={landingStyles.productPreviewRow}>
              <span>이름</span>
              <strong className={landingStyles.productPreviewRowValue}>홍길동</strong>
            </div>
            <div className={landingStyles.productPreviewRow}>
              <span>금액</span>
              <strong className={landingStyles.productPreviewRowValue}>₩12,000</strong>
            </div>
          </div>
          <div className={landingStyles.productPreviewStatus}>
            <span className={landingStyles.productPreviewStatusLabel}>확인 대기</span>
          </div>
          <div className={landingStyles.productPreviewField}>
            <span className={landingStyles.productPreviewFieldLabel}>확인자 이니셜</span>
            <strong className={landingStyles.productPreviewFieldValue}>HK</strong>
          </div>
          <div className={landingStyles.productPreviewActions}>
            <span className={landingStyles.productPreviewRejectAction}>거절</span>
            <span className={landingStyles.productPreviewConfirmAction}>확정</span>
          </div>
        </div>
      );
      break;
    case "03":
      screen = (
        <div className={previewScreenClassName}>
          <p className={landingStyles.productPreviewEyebrow}>매장 장부</p>
          <h3 className={landingStyles.productPreviewTitle}>월별 장부</h3>
          <div className={landingStyles.productPreviewTotal}>
            <span className={landingStyles.productPreviewTotalLabel}>조회 기간 합계</span>
            <strong className={landingStyles.productPreviewTotalValue}>₩80,000</strong>
          </div>
          <div className={landingStyles.productPreviewRows}>
            <div className={landingStyles.productPreviewMetric}>
              <span className={landingStyles.productPreviewMetricLabel}>결제 전</span>
              <strong className={landingStyles.productPreviewMetricAccent}>₩48,000</strong>
            </div>
            <div className={landingStyles.productPreviewMetric}>
              <span className={landingStyles.productPreviewMetricLabel}>결제 완료</span>
              <strong className={landingStyles.productPreviewMetricValue}>₩0</strong>
            </div>
            <div className={landingStyles.productPreviewMetric}>
              <span className={landingStyles.productPreviewMetricLabel}>결제 완료(선불)</span>
              <strong className={landingStyles.productPreviewMetricValue}>₩32,000</strong>
            </div>
          </div>
          <span className={landingStyles.productPreviewLedgerAction}>결제할 금액 보기</span>
        </div>
      );
      break;
    default:
      break;
  }

  return (
    <div className={`${landingStyles.productPreview} ${className}`.trim()} data-product-flow-preview data-preview-step={step.number} aria-hidden="true">
      <div className={landingStyles.productPreviewTopline}>
        <span className={landingStyles.productPreviewLabel}>화면 예시</span>
      </div>
      {screen}
    </div>
  );
}

export function LandingFlow({ steps }: LandingFlowProps) {
  const [activeIndex, setActiveIndex] = useState(0);
  const [previewMotionStep, setPreviewMotionStep] = useState<string | null>(null);
  const cardRefs = useRef<Array<HTMLLIElement | null>>([]);
  const activeIndexRef = useRef(0);
  const stepCount = steps.length;
  const safeActiveIndex = stepCount === 0 ? 0 : Math.min(activeIndex, stepCount - 1);
  const activeStep = steps[safeActiveIndex];

  useEffect(() => {
    if (typeof window === "undefined" || stepCount === 0 || typeof IntersectionObserver === "undefined") {
      return;
    }

    const cards = cardRefs.current.filter((card): card is HTMLLIElement => card !== null);

    if (cards.length === 0) {
      return;
    }

    const visibleCards = new Map<Element, IntersectionObserverEntry>();
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            visibleCards.set(entry.target, entry);
          } else {
            visibleCards.delete(entry.target);
          }
        });

        if (visibleCards.size === 0) {
          return;
        }

        const viewportAnchor = window.innerHeight * 0.38;
        const currentEntry = Array.from(visibleCards.values()).reduce((closest, entry) => {
          const closestDistance = Math.abs(closest.boundingClientRect.top - viewportAnchor);
          const entryDistance = Math.abs(entry.boundingClientRect.top - viewportAnchor);
          return entryDistance < closestDistance ? entry : closest;
        });
        const nextIndex = Number((currentEntry.target as HTMLElement).dataset.flowStepIndex);

        if (Number.isInteger(nextIndex) && nextIndex >= 0 && nextIndex < stepCount && nextIndex !== activeIndexRef.current) {
          activeIndexRef.current = nextIndex;
          setActiveIndex(nextIndex);
          setPreviewMotionStep(steps[nextIndex].number);
        }
      },
      {
        rootMargin: "-18% 0px -56% 0px",
        threshold: [0, 0.25, 0.6],
      },
    );

    cards.forEach((card) => observer.observe(card));

    return () => observer.disconnect();
  }, [stepCount]);

  if (!activeStep) {
    return null;
  }

  return (
    <div className={landingStyles.flowLayout} data-active-step={activeStep.number}>
      <ol className={landingStyles.flowSteps} aria-label="사용 흐름 단계">
        {steps.map((step, index) => (
          <FlowStepCard
            key={step.number}
            {...step}
            active={index === safeActiveIndex}
            cardRef={(card) => {
              cardRefs.current[index] = card;
            }}
            index={index}
            preview={
              <div className={landingStyles.mobileFlowPreview} data-product-flow-preview-placement="mobile">
                <ProductFlowPreview step={step} />
              </div>
            }
          />
        ))}
      </ol>

      <aside className={landingStyles.flowVisual} data-product-flow-preview-placement="desktop" aria-hidden="true">
        <div className={landingStyles.flowVisualSticky}>
          <div className={landingStyles.flowVisualShell}>
            <ProductFlowPreview
              key={activeStep.number}
              step={activeStep}
              className={previewMotionStep === activeStep.number ? motionStyles.desktopPreviewPrimary : undefined}
              screenClassName={previewMotionStep === activeStep.number ? motionStyles.desktopPreviewSecondary : undefined}
            />
          </div>
        </div>
      </aside>
    </div>
  );
}
