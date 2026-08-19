import type { Metadata } from "next";
import { BrandWordmark, SectionHeading, Wordmark, type LandingFlowStep } from "./Landing.components";
import { LandingBrandIntro } from "./LandingBrandIntro";
import { LANDING_BRAND_INTRO_PREFLIGHT_SCRIPT } from "./Landing.intro.config";
import { LandingFlow } from "./LandingFlow";
import { landingStyles } from "./Landing.styles";
import motionStyles from "./Landing.motion.module.css";

export const metadata: Metadata = {
  title: "TIEAT | 식대 장부를 한 큐에",
  description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
  openGraph: {
    title: "TIEAT | 식대 장부를 한 큐에",
    description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
    type: "website",
    images: [
      {
        url: "/og/tieat-og.png",
        width: 1200,
        height: 630,
        alt: "TIEAT 식대 장부 흐름",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "TIEAT | 식대 장부를 한 큐에",
    description: "QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에 보는 TIEAT 식대 장부",
    images: ["/og/tieat-og.png"],
  },
};

const flowSteps: LandingFlowStep[] = [
  {
    number: "01",
    title: "QR로 입력",
    description: "협력사 직원이 휴대폰으로 QR을 열고 식대 사용 내용을 남깁니다.",
  },
  {
    number: "02",
    title: "매장에서 확인",
    description: "매장 직원은 태블릿에서 입력 내용을 확인하고 필요한 것만 확정합니다.",
  },
  {
    number: "03",
    title: "장부에서 결제 확인",
    description: "확정된 사용 내역과 결제할 금액을 한곳에서 확인합니다.",
  },
];

const trustPoints = [
  "입력과 확인의 상태를 분리해, 확정 전 내용을 구분합니다.",
  "TIEAT는 결제 금액을 보관하거나 결제를 대신 실행하지 않습니다.",
  "매장 장부와 확인 흐름을 연결해 직원이 같은 내용을 바라보게 합니다.",
] as const;

export default function Home() {
  return (
    <div className={landingStyles.page} data-tieat-landing-root suppressHydrationWarning>
      <script data-tieat-preflight dangerouslySetInnerHTML={{ __html: LANDING_BRAND_INTRO_PREFLIGHT_SCRIPT }} />
      <header className={landingStyles.header}>
        <Wordmark />
        <a className={landingStyles.headerLink} href="#flow">
          어떻게 쓰나요?
        </a>
      </header>

      <LandingBrandIntro />

      <main>
        <section
          className={`${landingStyles.hero} ${motionStyles.sectionReveal}`}
          aria-labelledby="hero-title"
          data-hero-section
        >
          <div className={landingStyles.heroCopy}>
            <div className={landingStyles.heroBrand} data-hero-brand role="img" aria-label="TIEAT">
              <BrandWordmark variant="hero" />
            </div>
            <p className={landingStyles.eyebrow}>자동으로 기록이 쌓이는 장부</p>
            <h1 id="hero-title" className={landingStyles.heroTitle}>
              기록은 가볍게,
              <br />
              확인은 분명하게.
            </h1>
            <p className={landingStyles.heroDescription}>
              QR 입력부터 매장 확인, 결제할 금액 확인까지 한 큐에.
            </p>
            <div className={landingStyles.heroActions}>
              <a className={landingStyles.primaryAction} href="/store/signup">
                서비스 시작하기
                <span aria-hidden="true">→</span>
              </a>
              <a className={landingStyles.secondaryAction} href="#contact">
                문의하기
              </a>
            </div>
            <p className={landingStyles.heroNote}>협력사 직원은 별도 설치 없이 휴대폰에서 시작할 수 있습니다.</p>
          </div>

          <div className={landingStyles.heroPreview} aria-label="TIEAT 장부 흐름 미리보기">
            <div className={landingStyles.previewTopline}>
              <span className={landingStyles.previewDot} aria-hidden="true" />
              <span>오늘의 장부</span>
              <span className={landingStyles.previewDate}>08.15</span>
            </div>
            <div className={landingStyles.previewAmount}>
              <span>확인할 사용 내역</span>
              <strong>3건</strong>
            </div>
            <div className={landingStyles.previewRows}>
              <div className={landingStyles.previewRow}>
                <span>
                  <span className={landingStyles.previewIcon} aria-hidden="true">↗</span>
                  협력사 A
                </span>
                <strong>₩48,000</strong>
              </div>
              <div className={landingStyles.previewRow}>
                <span>
                  <span className={landingStyles.previewIcon} aria-hidden="true">↗</span>
                  협력사 B
                </span>
                <strong>₩32,000</strong>
              </div>
              <div className={landingStyles.previewRowMuted}>
                <span>결제 전</span>
                <strong>₩80,000</strong>
              </div>
            </div>
            <p className={landingStyles.previewCaption}>매장 직원이 확인한 기록만 장부에 남습니다.</p>
          </div>
        </section>

        <section id="flow" className={landingStyles.section} aria-labelledby="flow-title">
          <SectionHeading
            id="flow-title"
            eyebrow="한 번에 이어지는 흐름"
            title="입력부터 결제 확인까지"
            description="바쁜 매장에서도 누가, 무엇을, 어디까지 확인했는지 쉽게 구분할 수 있습니다."
          />
          <LandingFlow steps={flowSteps} />
        </section>

        <section className={landingStyles.trustSection} aria-labelledby="trust-title">
          <SectionHeading
            id="trust-title"
            className={landingStyles.trustCopy}
            eyebrow="신뢰를 위한 경계"
            title="기록과 결제를 분명하게 나눕니다."
            description="TIEAT는 매장 안에서 필요한 기록과 확인에 집중합니다. 결제 자체를 대신한다고 약속하지 않습니다."
          />
          <ul className={landingStyles.trustList}>
            {trustPoints.map((point) => (
              <li className={landingStyles.trustItem} key={point}>
                <span className={landingStyles.trustMark} aria-hidden="true">✓</span>
                <span>{point}</span>
              </li>
            ))}
          </ul>
        </section>

        <section id="contact" className={landingStyles.contactSection} aria-labelledby="contact-title">
          <div className={landingStyles.contactCard}>
            <div>
              <p className={landingStyles.eyebrow}>도입 문의</p>
              <h2 id="contact-title" className={landingStyles.contactTitle}>매장에 맞는 시작 방법을 준비하고 있습니다.</h2>
              <p className={landingStyles.contactDescription}>도입 문의 채널을 준비 중입니다.</p>
            </div>
            <p className={landingStyles.contactStatus} role="status">채널이 열리면 이곳에서 안내해 드리겠습니다.</p>
          </div>
        </section>
      </main>

      <footer className={landingStyles.footer}>
        <div className={landingStyles.footerInner}>
          <div className={landingStyles.footerTop}>
            <div>
              <a className={landingStyles.footerBrand} href="/" aria-label="TIEAT 홈">
                <img className={landingStyles.footerIcon} src="/icon.svg" alt="" />
                <span className={landingStyles.footerBrandName}>TIEAT</span>
              </a>
              <p className={landingStyles.footerDescription}>QR 입력부터 매장 확인과 장부까지.</p>
            </div>
            <nav className={landingStyles.footerNav} aria-label="하단 메뉴">
              <a className={landingStyles.footerNavLink} href="/store/signup">서비스 시작하기</a>
              <a className={landingStyles.footerNavLink} href="#flow">사용 방법</a>
              <a className={landingStyles.footerNavLink} href="#contact">문의하기</a>
            </nav>
          </div>
          <div className={landingStyles.footerBottom}>
            <span>© 2026 TIEAT. All rights reserved.</span>
            <span>현장 기록을 더 단순하게</span>
          </div>
        </div>
      </footer>
    </div>
  );
}
