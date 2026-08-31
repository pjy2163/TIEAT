"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import QRCode from "qrcode";
import {
  getStoreMealUsageQr,
  renewStoreMealUsageQr,
  type StoreMealUsageQrView as StoreMealUsageQrApiView,
} from "@/lib/store-meal-usage-qr-api";
import { useStorePartnerContext } from "../StorePartnerContext";
import { storeMealUsageQrViewStyles as styles } from "./StoreMealUsageQrView.styles";

type RequestState = "loading" | "ready" | "error";

const qrOptions = {
  errorCorrectionLevel: "M" as const,
  margin: 2,
  width: 560,
  color: {
    dark: "#111827",
    light: "#ffffff",
  },
};

function formatExpiry(value: string): string {
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}

export function StoreMealUsageQrView() {
  const { storeDisplayName } = useStorePartnerContext();
  const [requestState, setRequestState] = useState<RequestState>("loading");
  const [view, setView] = useState<StoreMealUsageQrApiView | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [renewalErrorMessage, setRenewalErrorMessage] = useState<string | null>(null);
  const [renewalState, setRenewalState] = useState<"idle" | "loading">("idle");
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    let active = true;
    setRequestState("loading");
    setView(null);
    setQrDataUrl(null);
    setErrorMessage(null);
    setRenewalErrorMessage(null);
    setRenewalState("idle");

    void getStoreMealUsageQr()
      .then((nextView) => {
        if (!active) return;
        setView(nextView);
        setRequestState("ready");
      })
      .catch(() => {
        if (!active) return;
        setRequestState("error");
        setErrorMessage("QR코드를 불러오지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.");
      });

    return () => {
      active = false;
    };
  }, [retryKey]);

  async function renewExpiredQr() {
    setRenewalState("loading");
    setRenewalErrorMessage(null);
    try {
      setView(await renewStoreMealUsageQr());
    } catch {
      setRenewalErrorMessage("QR코드를 연장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setRenewalState("idle");
    }
  }

  useEffect(() => {
    let active = true;
    if (view?.status !== "AVAILABLE" || view.publicPath === null) {
      setQrDataUrl(null);
      return () => {
        active = false;
      };
    }

    setQrDataUrl(null);
    setErrorMessage(null);
    const qrPayload = new URL(view.publicPath, window.location.origin).toString();
    void QRCode.toDataURL(qrPayload, qrOptions)
      .then((dataUrl) => {
        if (active) setQrDataUrl(dataUrl);
      })
      .catch(() => {
        if (!active) return;
        setErrorMessage("QR코드를 표시하지 못했습니다. 다시 시도해 주세요.");
      });

    return () => {
      active = false;
    };
  }, [view]);

  const storeName = storeDisplayName ?? "매장";

  if (requestState === "loading") {
    return (
      <main aria-busy="true" className={styles.page}>
        <section aria-label="매장 QR코드 불러오는 중" className={styles.container}>
          <p className={styles.eyebrow}>TIEAT STORE</p>
          <h1 className={styles.title}>{storeName} QR코드</h1>
          <p className={styles.description}>매장 QR코드를 불러오는 중입니다.</p>
          <div className={styles.state} role="status">
            <p className={styles.qrLoading}>잠시만 기다려 주세요.</p>
          </div>
        </section>
      </main>
    );
  }

  if (requestState === "error" || errorMessage !== null) {
    return (
      <main className={styles.page}>
        <section aria-live="polite" className={styles.container}>
          <p className={styles.eyebrow}>TIEAT STORE</p>
          <h1 className={styles.title}>{storeName} QR코드</h1>
          <div className={styles.state} role="alert">
            <h2 className={styles.stateTitle}>QR코드를 불러오지 못했습니다</h2>
            <p className={styles.stateDescription}>{errorMessage ?? "잠시 후 다시 시도해 주세요."}</p>
            <button className={styles.stateAction} onClick={() => setRetryKey((current) => current + 1)} type="button">
              다시 시도
            </button>
          </div>
        </section>
      </main>
    );
  }

  if (view?.status === "AVAILABLE" && view.publicPath !== null && view.expiresAt !== null) {
    return (
      <main className={styles.page}>
        <section aria-labelledby="store-meal-usage-qr-title" className={styles.container}>
          <p className={styles.eyebrow}>TIEAT STORE</p>
          <h1 className={styles.title} id="store-meal-usage-qr-title">{storeName} QR코드</h1>
          <p className={styles.description}>협력사 직원이 식대 사용을 등록할 때 스캔하는 매장 QR코드입니다.</p>
          <div className={styles.card}>
            <div className={styles.qrFrame} aria-busy={qrDataUrl === null}>
              {qrDataUrl === null ? (
                <p className={styles.qrLoading} role="status">QR코드를 준비하는 중입니다.</p>
              ) : (
                <img alt={`${storeName} QR코드`} className={styles.qrImage} src={qrDataUrl} />
              )}
            </div>
            <p className={styles.expiry}>만료: {formatExpiry(view.expiresAt)}</p>
          </div>
          <Link className={styles.backLink} href="/store/meal-usages/months">장부로 돌아가기</Link>
        </section>
      </main>
    );
  }

  const statusCopy = statusMessage(view?.status);
  return (
    <main className={styles.page}>
      <section aria-live="polite" className={styles.container}>
        <p className={styles.eyebrow}>TIEAT STORE</p>
        <h1 className={styles.title}>{storeName} QR코드</h1>
        <div className={styles.state}>
          <h2 className={styles.stateTitle}>{statusCopy.title}</h2>
          <p className={styles.stateDescription}>{statusCopy.description}</p>
          {view?.status === "EXPIRED" && (
            <>
              <button
                className={styles.stateAction}
                disabled={renewalState === "loading"}
                onClick={() => void renewExpiredQr()}
                type="button"
              >
                {renewalState === "loading" ? "연장하는 중..." : "90일 연장하기"}
              </button>
              {renewalErrorMessage !== null && (
                <p className={styles.stateDescription} role="alert">{renewalErrorMessage}</p>
              )}
            </>
          )}
          <Link className={styles.stateAction} href="/store/meal-usages/months">장부로 돌아가기</Link>
        </div>
      </section>
    </main>
  );
}

function statusMessage(status: StoreMealUsageQrApiView["status"] | undefined): { title: string; description: string } {
  switch (status) {
    case "EXPIRED":
      return {
        title: "QR코드가 만료되었습니다",
        description: "기존 QR은 즉시 사용할 수 없습니다. 매장 담당자가 아래 버튼으로 같은 QR을 90일 연장해 주세요.",
      };
    case "REISSUE_REQUIRED":
      return {
        title: "QR코드를 다시 발급해 주세요",
        description: "기존 QR코드는 보안상 다시 표시할 수 없습니다. 관리자에게 재발급을 요청해 주세요.",
      };
    case "NOT_AVAILABLE":
    default:
      return {
        title: "QR코드가 아직 없습니다",
        description: "현재 사용할 수 있는 QR코드가 없습니다. 관리자에게 발급 상태를 확인해 주세요.",
      };
  }
}
