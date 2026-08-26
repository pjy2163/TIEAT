"use client";

import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, reauthenticateStoreSession } from "@/lib/store-api";
import type { PartnerKindFilter } from "@/lib/partner-kind";
import {
  archiveStorePartner,
  getArchivePinStatus,
  getStoreProfile,
  setArchivePin,
  updateStorePartnerPaymentType,
  type StorePartner,
  type StoreProfile,
} from "@/lib/store-partner-api";
import { useStorePartnerContext } from "../StorePartnerContext";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";
import { StorePartnerArchiveDialogs } from "./StorePartnerArchiveDialogs";
import { StorePartnerDetailDialog } from "./StorePartnerDetailDialog";
import { ProfileState } from "./ProfileState";
import { StoreProfileOverview } from "./StoreProfileOverview";
import { apiErrorMessage, ledgerHref, parsePositiveAmount } from "./StoreProfileView.helpers";
import type { ArchiveRetrySnapshot, StoreProfileModalMode } from "./StoreProfileView.types";

type ProfileViewState = "loading" | "ready" | "forbidden" | "error" | "redirecting";

function isReauthenticationRequired(error: unknown): boolean {
  return error instanceof ApiError
    && error.status === 403
    && error.errorCode === "PASSWORD_REAUTHENTICATION_REQUIRED";
}

export function StoreProfileView() {
  const router = useRouter();
  const {
    partners,
    directoryState,
    refreshDirectory,
    upsertPartner,
  } = useStorePartnerContext();
  const [profile, setProfile] = useState<StoreProfile | null>(null);
  const [viewState, setViewState] = useState<ProfileViewState>("loading");
  const [partnerKindFilter, setPartnerKindFilter] = useState<PartnerKindFilter>("ALL");
  const [modalMode, setModalMode] = useState<StoreProfileModalMode | null>(null);
  const [activePartner, setActivePartner] = useState<StorePartner | null>(null);
  const [pinConfigured, setPinConfigured] = useState<boolean | null>(null);
  const [pinStatusLoading, setPinStatusLoading] = useState(false);
  const [pinStatusError, setPinStatusError] = useState<string | null>(null);
  const [paymentType, setPaymentType] = useState<StorePartner["paymentType"]>("POSTPAID");
  const [prepaidBalance, setPrepaidBalance] = useState("");
  const [paymentError, setPaymentError] = useState<string | null>(null);
  const [isSavingPayment, setIsSavingPayment] = useState(false);
  const [archivePin, setArchivePinValue] = useState("");
  const [archiveError, setArchiveError] = useState<string | null>(null);
  const [isArchiving, setIsArchiving] = useState(false);
  const [archiveRetry, setArchiveRetry] = useState<ArchiveRetrySnapshot | null>(null);
  const [archiveRetryTerminal, setArchiveRetryTerminal] = useState(false);
  const [reauthPassword, setReauthPassword] = useState("");
  const [reauthError, setReauthError] = useState<string | null>(null);
  const [isReauthenticating, setIsReauthenticating] = useState(false);
  const reauthPasswordRef = useRef<HTMLInputElement>(null);
  const [currentPin, setCurrentPin] = useState("");
  const [accountPassword, setAccountPassword] = useState("");
  const [newPin, setNewPin] = useState("");
  const [newPinConfirmation, setNewPinConfirmation] = useState("");
  const [pinError, setPinError] = useState<string | null>(null);
  const [isSavingPin, setIsSavingPin] = useState(false);

  const loadProfile = useCallback(() => {
    setViewState("loading");
    void getStoreProfile()
      .then((nextProfile) => {
        setProfile(nextProfile);
        setViewState("ready");
      })
      .catch((error: unknown) => {
        setProfile(null);
        if (error instanceof ApiError && error.status === 401) {
          setViewState("redirecting");
          router.replace("/store/login?next=/store/profile");
          return;
        }
        setViewState(error instanceof ApiError && error.status === 403 ? "forbidden" : "error");
      });
  }, [router]);

  const closeModal = useCallback(() => {
    setModalMode(null);
    setActivePartner(null);
    setPaymentError(null);
    setArchiveError(null);
    setArchiveRetry(null);
    setArchiveRetryTerminal(false);
    setReauthPassword("");
    setReauthError(null);
    setPinError(null);
    setArchivePinValue("");
    setCurrentPin("");
    setAccountPassword("");
    setNewPin("");
    setNewPinConfirmation("");
  }, []);

  useEffect(() => {
    if (modalMode === "archive-reauth" && !isReauthenticating) {
      reauthPasswordRef.current?.focus();
    }
  }, [isReauthenticating, modalMode, reauthError]);

  useEffect(() => {
    if (modalMode === null) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !isArchiving && !isReauthenticating) closeModal();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [closeModal, isArchiving, isReauthenticating, modalMode]);

  const refreshPinStatus = useCallback(() => {
    setPinStatusLoading(true);
    setPinStatusError(null);
    void getArchivePinStatus()
      .then((status) => setPinConfigured(status.configured))
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          closeModal();
          router.replace("/store/login?next=/store/profile");
          return;
        }
        setPinStatusError("삭제 PIN 상태를 확인하지 못했습니다.");
      })
      .finally(() => setPinStatusLoading(false));
  }, [closeModal, router]);

  function openDetail(partner: StorePartner) {
    setActivePartner(partner);
    setModalMode("detail");
    refreshPinStatus();
  }

  function openPaymentEditor() {
    if (!activePartner) return;
    setPaymentType(activePartner.paymentType === "POSTPAID" ? "PREPAID_WITH_RECEIVABLE_OVERFLOW" : "POSTPAID");
    setPrepaidBalance("");
    setPaymentError(null);
    setModalMode("payment");
  }

  function openArchiveWarning() {
    setArchiveError(null);
    setArchiveRetry(null);
    setArchiveRetryTerminal(false);
    setModalMode("archive-warning");
  }

  function openArchivePin() {
    setArchivePinValue("");
    setArchiveError(null);
    setArchiveRetry(null);
    setArchiveRetryTerminal(false);
    setModalMode("archive-pin");
  }

  function openPinSettings() {
    setCurrentPin("");
    setAccountPassword("");
    setNewPin("");
    setNewPinConfirmation("");
    setPinError(null);
    setModalMode("pin-settings");
  }

  async function savePaymentType() {
    if (!activePartner || isSavingPayment) return;
    const nextBalance = paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW"
      ? parsePositiveAmount(prepaidBalance)
      : null;
    if (paymentType === "PREPAID_WITH_RECEIVABLE_OVERFLOW" && nextBalance === null) {
      setPaymentError("전환 시 선불 잔액은 1원 이상 입력해 주세요.");
      return;
    }
    setIsSavingPayment(true);
    setPaymentError(null);
    try {
      const updated = await updateStorePartnerPaymentType(activePartner.mealContractId, {
        expectedPaymentType: activePartner.paymentType,
        paymentType,
        prepaidBalanceMinor: nextBalance,
      });
      upsertPartner(updated);
      setActivePartner(updated);
      setModalMode("detail");
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        closeModal();
        router.replace("/store/login?next=/store/profile");
        return;
      }
      setPaymentError(apiErrorMessage(error, "결제 유형 변경에 실패했습니다. 다시 시도해 주세요."));
    } finally {
      setIsSavingPayment(false);
    }
  }

  function handleArchivePinFailure(error: unknown) {
    if (error instanceof ApiError && error.status === 401) {
      closeModal();
      router.replace("/store/login?next=/store/profile");
      return;
    }
    if (error instanceof ApiError && error.status === 403) {
      setArchiveError("삭제 PIN이 올바르지 않습니다.");
    } else if (error instanceof ApiError && error.status === 429) {
      setArchiveError("삭제 PIN 입력이 잠겼습니다. 15분 후 다시 시도해 주세요.");
    } else if (error instanceof ApiError && error.status === 409) {
      setArchiveError("미정산 금액이나 남은 선불 잔액이 있어 삭제할 수 없습니다.");
    } else {
      setArchiveError("협력사 삭제에 실패했습니다. 다시 시도해 주세요.");
    }
  }

  async function saveArchivePin() {
    if (!activePartner || isArchiving || isReauthenticating || archiveRetryTerminal) return;
    if (!/^\d{4}$/.test(archivePin)) {
      setArchiveError("삭제 PIN은 숫자 4자리로 입력해 주세요.");
      return;
    }
    setIsArchiving(true);
    setArchiveError(null);
    try {
      await archiveStorePartner(activePartner.mealContractId, archivePin);
      closeModal();
      refreshDirectory();
    } catch (error: unknown) {
      if (isReauthenticationRequired(error)) {
        setArchiveRetry({ mealContractId: activePartner.mealContractId, pin: archivePin });
        setReauthPassword("");
        setReauthError(null);
        setModalMode("archive-reauth");
      } else {
        handleArchivePinFailure(error);
      }
    } finally {
      setIsArchiving(false);
    }
  }

  async function submitReauthentication(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!archiveRetry || isReauthenticating) return;
    const snapshot = archiveRetry;
    setIsReauthenticating(true);
    setReauthError(null);
    try {
      await reauthenticateStoreSession(reauthPassword);
      setReauthPassword("");
      setArchiveRetry(null);
      try {
        await archiveStorePartner(snapshot.mealContractId, snapshot.pin);
        closeModal();
        refreshDirectory();
      } catch (error: unknown) {
        setArchivePinValue(snapshot.pin);
        setModalMode("archive-pin");
        if (isReauthenticationRequired(error)) {
          setArchiveRetryTerminal(true);
          setArchiveError("비밀번호 재확인이 다시 필요합니다. 이 삭제 요청은 종료되었습니다.");
        } else {
          handleArchivePinFailure(error);
        }
      }
    } catch (error: unknown) {
      setReauthPassword("");
      if (error instanceof ApiError && error.status === 401 && error.errorCode === "AUTHENTICATION_REQUIRED") {
        closeModal();
        router.replace("/store/login?next=/store/profile");
      } else {
        setReauthError("비밀번호 재확인에 실패했습니다. 다시 시도해 주세요.");
      }
    } finally {
      setIsReauthenticating(false);
    }
  }

  async function savePinSettings() {
    if (isSavingPin) return;
    if (pinConfigured && !/^\d{4}$/.test(currentPin)) {
      setPinError("현재 삭제 PIN은 숫자 4자리로 입력해 주세요.");
      return;
    }
    if (!/^\d{4}$/.test(newPin)) {
      setPinError("새 삭제 PIN은 숫자 4자리로 입력해 주세요.");
      return;
    }
    if (!/^\d{4}$/.test(newPinConfirmation) || newPin !== newPinConfirmation) {
      setPinError("새 삭제 PIN을 같은 숫자 4자리로 한 번 더 입력해 주세요.");
      return;
    }
    if (!pinConfigured && accountPassword.length === 0) {
      setPinError("계정 비밀번호를 입력해 주세요.");
      return;
    }
    setIsSavingPin(true);
    setPinError(null);
    try {
      await setArchivePin({
        currentPin: pinConfigured ? currentPin : null,
        accountPassword: pinConfigured ? null : accountPassword,
        newPin,
        newPinConfirmation,
      });
      setPinConfigured(true);
      setModalMode("detail");
      setCurrentPin("");
      setAccountPassword("");
      setNewPin("");
      setNewPinConfirmation("");
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) {
        closeModal();
        router.replace("/store/login?next=/store/profile");
        return;
      }
      if (error instanceof ApiError && error.status === 403 && error.errorCode === "STORE_ARCHIVE_ACCOUNT_PASSWORD_INVALID") {
        setPinError("계정 비밀번호가 올바르지 않습니다.");
      } else if (error instanceof ApiError && error.status === 403) {
        setPinError("현재 삭제 PIN이 올바르지 않습니다.");
      } else if (error instanceof ApiError && error.status === 429) {
        setPinError("삭제 PIN 입력이 잠겼습니다. 15분 후 다시 시도해 주세요.");
      } else if (error instanceof ApiError && error.status === 400) {
        setPinError("삭제 PIN은 숫자 4자리로 입력해 주세요.");
      } else {
        setPinError("삭제 PIN을 저장하지 못했습니다. 다시 시도해 주세요.");
      }
    } finally {
      setIsSavingPin(false);
    }
  }

  useEffect(() => {
    loadProfile();
  }, [loadProfile]);

  if (viewState === "loading") {
    return (
      <main className={styles.page} aria-busy="true">
        <section className={styles.container} aria-label="마이페이지 불러오는 중">
          <div className="h-3 w-24 animate-pulse rounded-sm bg-[var(--surface)]" />
          <div className="mt-3 h-9 w-44 animate-pulse rounded-sm bg-[var(--surface)]" />
          <div className="mt-8 grid gap-5 lg:grid-cols-2">
            <div className={`${styles.card} h-48 animate-pulse bg-[var(--surface)]`} />
            <div className={`${styles.card} h-64 animate-pulse bg-[var(--surface)]`} />
          </div>
        </section>
      </main>
    );
  }

  if (viewState === "redirecting") {
    return <ProfileState title="로그인 화면으로 이동 중입니다" description="매장 정보를 다시 확인하려면 로그인해 주세요." />;
  }

  if (viewState === "forbidden") {
    return (
      <ProfileState
        actionHref="/store/meal-usages/months"
        actionLabel="전체 장부로 이동"
        title="마이페이지를 볼 수 없습니다"
        description="이 계정으로는 매장 프로필에 접근할 수 없습니다. 권한을 확인한 뒤 다시 시도해 주세요."
      />
    );
  }

  if (viewState === "error" || profile === null) {
    return (
      <ProfileState
        onRetry={loadProfile}
        title="마이페이지를 불러오지 못했습니다"
        description="네트워크 상태를 확인한 뒤 다시 시도해 주세요."
      />
    );
  }

  return (
    <main className={styles.page}>
      <section className={styles.container} aria-labelledby="store-profile-title">
        <StoreProfileOverview
          directoryState={directoryState}
          onOpenDetail={openDetail}
          onPartnerKindFilterChange={setPartnerKindFilter}
          onRefreshDirectory={refreshDirectory}
          partnerKindFilter={partnerKindFilter}
          partners={partners}
          profile={profile}
        />

        {activePartner && modalMode ? (
          <dialog
            aria-labelledby={modalMode === "archive-reauth" ? "archive-reauth-title" : "store-partner-dialog-title"}
            aria-modal="true"
            className={styles.dialog}
            open
          >
            <div className={styles.dialogCard}>
              <StorePartnerDetailDialog
                activePartner={activePartner}
                isSavingPayment={isSavingPayment}
                modalMode={modalMode}
                onClose={closeModal}
                onOpenArchiveWarning={openArchiveWarning}
                onOpenPaymentEditor={openPaymentEditor}
                onOpenPinSettings={openPinSettings}
                onPaymentCancel={() => setModalMode("detail")}
                onPaymentTypeChange={setPaymentType}
                onPrepaidBalanceChange={setPrepaidBalance}
                onSavePaymentType={savePaymentType}
                paymentError={paymentError}
                paymentType={paymentType}
                pinConfigured={pinConfigured}
                pinStatusError={pinStatusError}
                pinStatusLoading={pinStatusLoading}
                prepaidBalance={prepaidBalance}
              />
              <StorePartnerArchiveDialogs
                accountPassword={accountPassword}
                activePartner={activePartner}
                archiveError={archiveError}
                archivePin={archivePin}
                archiveRetryTerminal={archiveRetryTerminal}
                currentPin={currentPin}
                isArchiving={isArchiving}
                isReauthenticating={isReauthenticating}
                isSavingPin={isSavingPin}
                modalMode={modalMode}
                newPin={newPin}
                newPinConfirmation={newPinConfirmation}
                onAccountPasswordChange={setAccountPassword}
                onArchivePinChange={(value) => setArchivePinValue(value.replace(/[^0-9]/g, "").slice(0, 4))}
                onClose={closeModal}
                onCurrentPinChange={(value) => setCurrentPin(value.replace(/[^0-9]/g, "").slice(0, 4))}
                onNewPinChange={(value) => setNewPin(value.replace(/[^0-9]/g, "").slice(0, 4))}
                onNewPinConfirmationChange={(value) => setNewPinConfirmation(value.replace(/[^0-9]/g, "").slice(0, 4))}
                onOpenArchivePin={openArchivePin}
                onOpenPinSettings={openPinSettings}
                onReauthPasswordChange={setReauthPassword}
                onSaveArchivePin={saveArchivePin}
                onSavePinSettings={savePinSettings}
                onSetModalMode={setModalMode}
                onSubmitReauthentication={submitReauthentication}
                pinConfigured={pinConfigured}
                pinError={pinError}
                reauthError={reauthError}
                reauthPassword={reauthPassword}
                reauthPasswordRef={reauthPasswordRef}
              />
            </div>
          </dialog>
        ) : null}
      </section>
    </main>
  );
}
