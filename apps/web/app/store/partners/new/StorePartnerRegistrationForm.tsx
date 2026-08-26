"use client";

import { type FormEvent, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ApiError, InvalidApiResponseError } from "@/lib/store-api";
import { createStorePartner, type StorePartner, type StorePartnerRegistration } from "@/lib/store-partner-api";
import { useStorePartnerContext } from "../../StorePartnerContext";
import { StorePartnerKindFields } from "../StorePartnerKindFields";
import { StorePartnerPaymentFields } from "../StorePartnerPaymentFields";
import { storePartnerRegistrationStyles as styles } from "./StorePartnerRegistrationForm.styles";

type PaymentType = StorePartnerRegistration["paymentType"];
type Submission = Readonly<{ payload: StorePartnerRegistration; idempotencyKey: string }>;
type LockState = "ambiguous" | "conflict" | "denied" | null;

// QR 노출 여부는 운영 설정으로 남기고, 등록 화면에서는 고객에게 묻지 않는다.
const DEFAULT_QR_SELECTABLE = true;

function makeIdempotencyKey(): string | null {
  try {
    const randomUuid = globalThis.crypto?.randomUUID;
    return typeof randomUuid === "function" ? randomUuid.call(globalThis.crypto) : null;
  } catch {
    return null;
  }
}

function isAmbiguous(error: unknown): boolean {
  return error instanceof InvalidApiResponseError
    || !(error instanceof ApiError)
    || error.status >= 500
    || (error.status === 403 && error.errorCode === "CSRF_TOKEN_INVALID");
}

export function StorePartnerRegistrationForm() {
  const router = useRouter();
  const { upsertPartner } = useStorePartnerContext();
  const [partnerName, setPartnerName] = useState("");
  const [partnerKind, setPartnerKind] = useState<StorePartnerRegistration["partnerKind"] | "">("");
  const [paymentType, setPaymentType] = useState<PaymentType | "">("");
  const [initialPrepaidBalanceMinor, setInitialPrepaidBalanceMinor] = useState("");
  const [representativePhone, setRepresentativePhone] = useState("");
  const [representativeEmail, setRepresentativeEmail] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [lockState, setLockState] = useState<LockState>(null);
  const [message, setMessage] = useState<string | null>(null);
  const submissionRef = useRef<Submission | null>(null);
  const submittingRef = useRef(false);

  function validatePayload(): StorePartnerRegistration | null {
    const trimmedName = partnerName.trim();
    if (trimmedName.length < 1 || trimmedName.length > 100) {
      setMessage("협력사명을 1~100자로 입력해 주세요.");
      return null;
    }
    if (partnerKind !== "INDIVIDUAL" && partnerKind !== "ORGANIZATION") {
      setMessage("협력사 유형을 선택해 주세요.");
      return null;
    }
    if (paymentType !== "POSTPAID" && paymentType !== "PREPAID_WITH_RECEIVABLE_OVERFLOW") {
      setMessage("결제 유형을 선택해 주세요.");
      return null;
    }
    let balance = 0;
    if (paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW") {
      const rawBalance = initialPrepaidBalanceMinor.replaceAll(",", "").trim();
      if (rawBalance.length === 0 || !/^\d+$/.test(rawBalance)) {
        setMessage("초기 선불 잔액을 0 이상의 정수로 입력해 주세요.");
        return null;
      }
      balance = Number(rawBalance);
      if (!Number.isSafeInteger(balance)) {
        setMessage("초기 선불 잔액을 안전한 범위의 정수로 입력해 주세요.");
        return null;
      }
    }

    const normalizedPhone = representativePhone.trim();
    if (normalizedPhone.length > 0
      && (normalizedPhone.length > 30
        || !/^[0-9+()\-\s]+$/.test(normalizedPhone)
        || !/\d/.test(normalizedPhone))) {
      setMessage("대표자 전화번호는 30자 이내 숫자·하이픈 형식으로 입력해 주세요.");
      return null;
    }
    const normalizedEmail = representativeEmail.trim();
    if (normalizedEmail.length > 0
      && (normalizedEmail.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail))) {
      setMessage("대표자 이메일 형식을 확인해 주세요.");
      return null;
    }

    return Object.freeze({
      partnerName: trimmedName,
      partnerKind,
      paymentType,
      initialPrepaidBalanceMinor: balance,
      qrSelectable: DEFAULT_QR_SELECTABLE,
      representativePhone: normalizedPhone || null,
      representativeEmail: normalizedEmail || null,
    });
  }

  async function submitWith(submission: Submission) {
    if (submittingRef.current) return;
    submittingRef.current = true;
    setIsSubmitting(true);
    setMessage(null);
    try {
      const partner = await createStorePartner(submission.payload, submission.idempotencyKey);
      upsertPartner(partner);
      router.replace(`/store/meal-usages/months?mealContractId=${encodeURIComponent(partner.mealContractId)}`);
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        submissionRef.current = null;
        setLockState(null);
        router.replace("/store/login?next=/store/meal-usages/months");
      } else if (error instanceof ApiError && error.status === 400) {
        submissionRef.current = null;
        setLockState(null);
        setMessage("입력 내용을 확인해 주세요.");
      } else if (error instanceof ApiError && error.status === 403 && error.errorCode !== "CSRF_TOKEN_INVALID") {
        setLockState("denied");
        setMessage("이 계정에는 협력사 등록 권한이 없습니다.");
      } else if (error instanceof ApiError && error.status === 409) {
        setLockState("conflict");
        setMessage("등록 키가 이미 다른 요청에 사용되었습니다. 협력사 목록에서 결과를 확인해 주세요.");
      } else if (isAmbiguous(error)) {
        setLockState("ambiguous");
        setMessage("등록 결과를 확인하지 못했습니다. 입력을 바꾸지 않고 같은 요청을 다시 시도해 주세요.");
      } else {
        setLockState("ambiguous");
        setMessage("등록 결과를 확인하지 못했습니다. 입력을 바꾸지 않고 같은 요청을 다시 시도해 주세요.");
      }
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current || lockState !== null) return;
    setMessage(null);
    const payload = validatePayload();
    if (payload === null) return;
    const idempotencyKey = makeIdempotencyKey();
    if (idempotencyKey === null) {
      setMessage("등록 키를 만들 수 없습니다. 브라우저를 새로고침한 뒤 다시 시도해 주세요.");
      return;
    }
    const submission = Object.freeze({ payload, idempotencyKey });
    submissionRef.current = submission;
    void submitWith(submission);
  }

  const fieldsDisabled = isSubmitting || lockState !== null;

  if (lockState === "denied") {
    return (
      <main className={styles.page}>
        <section className={`${styles.container} ${styles.denied}`} aria-live="polite">
          <h1 className={styles.deniedTitle}>협력사 등록 권한이 없습니다</h1>
          <p className={styles.deniedDescription}>{message}</p>
          <Link className={`${styles.secondaryAction} mt-5`} href="/store/meal-usages/months">전체 장부로 이동</Link>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <section className={styles.container} aria-labelledby="store-partner-registration-title">
        <div className={styles.card}>
          <p className={styles.eyebrow}>TIEAT STORE</p>
          <h1 className={styles.title} id="store-partner-registration-title">협력사 추가</h1>
          <p className={styles.description}>등록이 끝나면 해당 협력사의 월별 장부로 이동합니다. 입력한 결제 조건은 등록 시 함께 저장되며, 등록 전까지 자유롭게 선택할 수 있습니다. 이 설명은 나중에 자유롭게 변경 가능합니다.</p>

          <form className={styles.form} noValidate onSubmit={onSubmit}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="store-partner-name">협력사명</label>
              <input
                className={styles.input}
                disabled={fieldsDisabled}
                id="store-partner-name"
                maxLength={100}
                onChange={(event) => setPartnerName(event.target.value)}
                value={partnerName}
              />
            </div>

            <StorePartnerKindFields
              disabled={fieldsDisabled}
              onPartnerKindChange={setPartnerKind}
              partnerKind={partnerKind}
            />

            <StorePartnerPaymentFields
              disabled={fieldsDisabled}
              initialPrepaidBalanceMinor={initialPrepaidBalanceMinor}
              onInitialPrepaidBalanceMinorChange={setInitialPrepaidBalanceMinor}
              onPaymentTypeChange={setPaymentType}
              paymentType={paymentType}
            />

            <div className={styles.field}>
              <label className={styles.label} htmlFor="store-partner-representative-phone">대표자 전화번호 (선택)</label>
              <input
                aria-describedby="store-partner-representative-phone-hint"
                autoComplete="tel"
                className={styles.input}
                disabled={fieldsDisabled}
                id="store-partner-representative-phone"
                inputMode="tel"
                maxLength={30}
                onChange={(event) => setRepresentativePhone(event.target.value)}
                type="tel"
                value={representativePhone}
              />
              <p className={styles.hint} id="store-partner-representative-phone-hint">필요할 때 연락할 대표자 번호를 입력해 주세요.</p>
            </div>

            <div className={styles.field}>
              <label className={styles.label} htmlFor="store-partner-representative-email">대표자 이메일 (선택)</label>
              <input
                aria-describedby="store-partner-representative-email-hint"
                autoComplete="email"
                className={styles.input}
                disabled={fieldsDisabled}
                id="store-partner-representative-email"
                maxLength={254}
                onChange={(event) => setRepresentativeEmail(event.target.value)}
                type="email"
                value={representativeEmail}
              />
              <p className={styles.hint} id="store-partner-representative-email-hint">대표자 이메일을 입력해 주세요.</p>
            </div>

            {message ? <p className={lockState === "ambiguous" || lockState === "conflict" ? styles.notice : styles.error} role="alert">{message}</p> : null}
            {lockState === "ambiguous" ? (
              <div className={styles.actionRow}>
                <button className={styles.primaryAction} disabled={isSubmitting} onClick={() => {
                  const submission = submissionRef.current;
                  if (submission) void submitWith(submission);
                }} type="button">
                  {isSubmitting ? "확인 중…" : "같은 요청 다시 시도"}
                </button>
              </div>
            ) : null}
            {lockState === "conflict" ? (
              <div className={styles.actionRow}>
                <Link className={styles.secondaryAction} href="/store/profile">마이페이지에서 확인</Link>
                <Link className={styles.primaryAction} href="/store/meal-usages/months">전체 장부에서 확인</Link>
              </div>
            ) : null}
            {lockState === null ? (
              <div className={styles.actionRow}>
                <Link className={styles.secondaryAction} href="/store/profile">취소</Link>
                <button className={styles.primaryAction} disabled={isSubmitting} type="submit">
                  {isSubmitting ? "협력사 등록 중…" : "협력사 등록하기"}
                </button>
              </div>
            ) : null}
          </form>
        </div>
      </section>
    </main>
  );
}
