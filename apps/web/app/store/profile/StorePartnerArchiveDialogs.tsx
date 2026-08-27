import type { FormEventHandler, RefObject } from "react";
import type { StorePartner } from "@/lib/store-partner-api";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";
import type { StoreProfileModalMode } from "./StoreProfileView.types";

type StorePartnerArchiveDialogsProps = Readonly<{
  activePartner: StorePartner | null;
  modalMode: StoreProfileModalMode;
  pinConfigured: boolean | null;
  archivePin: string;
  archiveError: string | null;
  isArchiving: boolean;
  archiveRetryTerminal: boolean;
  reauthPassword: string;
  reauthPasswordRef: RefObject<HTMLInputElement | null>;
  reauthError: string | null;
  isReauthenticating: boolean;
  currentPin: string;
  accountPassword: string;
  newPin: string;
  newPinConfirmation: string;
  pinError: string | null;
  isSavingPin: boolean;
  onSetModalMode: (mode: StoreProfileModalMode) => void;
  onOpenArchivePin: () => void;
  onOpenPinSettings: () => void;
  onClose: () => void;
  onArchivePinChange: (value: string) => void;
  onSaveArchivePin: () => void;
  onReauthPasswordChange: (value: string) => void;
  onSubmitReauthentication: FormEventHandler<HTMLFormElement>;
  onCurrentPinChange: (value: string) => void;
  onAccountPasswordChange: (value: string) => void;
  onNewPinChange: (value: string) => void;
  onNewPinConfirmationChange: (value: string) => void;
  onSavePinSettings: () => void;
}>;

export function StorePartnerArchiveDialogs({
  activePartner,
  modalMode,
  pinConfigured,
  archivePin,
  archiveError,
  isArchiving,
  archiveRetryTerminal,
  reauthPassword,
  reauthPasswordRef,
  reauthError,
  isReauthenticating,
  currentPin,
  accountPassword,
  newPin,
  newPinConfirmation,
  pinError,
  isSavingPin,
  onSetModalMode,
  onOpenArchivePin,
  onOpenPinSettings,
  onClose,
  onArchivePinChange,
  onSaveArchivePin,
  onReauthPasswordChange,
  onSubmitReauthentication,
  onCurrentPinChange,
  onAccountPasswordChange,
  onNewPinChange,
  onNewPinConfirmationChange,
  onSavePinSettings,
}: StorePartnerArchiveDialogsProps) {
  if (modalMode === "archive-warning") {
    if (!activePartner) return null;
    return (
      <>
        <h2 className={styles.dialogTitle} id="store-partner-dialog-title">협력사를 삭제할까요?</h2>
        <p className={styles.dialogName}>{activePartner.partnerDisplayName}</p>
        <p className={styles.dialogDescription}>기존 장부는 보존되고 신규 사용만 중단됩니다. 삭제하려면 매장 공용 4자리 PIN이 필요합니다.</p>
        {archiveError ? <p className={styles.dialogError} role="alert">{archiveError}</p> : null}
        <div className={styles.dialogActions}>
          <button className={styles.dialogCancel} onClick={() => onSetModalMode("detail")} type="button">취소</button>
          <button className={styles.dialogDangerConfirm} disabled={pinConfigured === null} onClick={pinConfigured ? onOpenArchivePin : onOpenPinSettings} type="button">
            {pinConfigured === null ? "삭제 PIN 확인 중..." : pinConfigured ? "삭제 PIN 입력" : "삭제 PIN 설정"}
          </button>
        </div>
      </>
    );
  }

  if (modalMode === "archive-pin") {
    if (!activePartner) return null;
    return (
      <>
        <h2 className={styles.dialogTitle} id="store-partner-dialog-title">삭제 PIN 확인</h2>
        <p className={styles.dialogName}>{activePartner.partnerDisplayName}</p>
        <label className={styles.fieldLabel} htmlFor="archive-pin">매장 공용 삭제 PIN</label>
        <input
          autoFocus
          className={styles.fieldInput}
          id="archive-pin"
          inputMode="numeric"
          maxLength={4}
          onChange={(event) => onArchivePinChange(event.target.value)}
          type="password"
          value={archivePin}
        />
        {archiveError ? <p className={styles.dialogError} role="alert">{archiveError}</p> : null}
        <div className={styles.dialogActions}>
          <button className={styles.dialogCancel} disabled={isArchiving || isReauthenticating} onClick={() => onSetModalMode("archive-warning")} type="button">뒤로</button>
          <button className={styles.dialogDangerConfirm} disabled={isArchiving || isReauthenticating || archiveRetryTerminal} onClick={onSaveArchivePin} type="button">
            {isArchiving ? "삭제 중..." : "최종 삭제"}
          </button>
        </div>
      </>
    );
  }

  if (modalMode === "archive-reauth") {
    return (
      <form className="space-y-5" onSubmit={onSubmitReauthentication}>
        <h2 className={styles.dialogTitle} id="archive-reauth-title">비밀번호 재확인</h2>
        <p className={styles.dialogDescription}>협력사를 삭제하려면 최근 계정 비밀번호를 다시 확인해 주세요.</p>
        <label className={styles.fieldLabel} htmlFor="archive-reauth-password">계정 비밀번호</label>
        <input
          aria-describedby={reauthError ? "archive-reauth-error" : undefined}
          autoComplete="current-password"
          autoFocus
          className={styles.fieldInput}
          id="archive-reauth-password"
          onChange={(event) => onReauthPasswordChange(event.target.value)}
          ref={reauthPasswordRef}
          required
          type="password"
          value={reauthPassword}
        />
        {reauthError ? <p className={styles.dialogError} id="archive-reauth-error" role="alert">{reauthError}</p> : null}
        <div className={styles.dialogActions}>
          <button className={styles.dialogCancel} disabled={isReauthenticating} onClick={onClose} type="button">취소</button>
          <button className={styles.dialogConfirm} disabled={isReauthenticating} type="submit">
            {isReauthenticating ? "확인 중..." : "비밀번호 확인"}
          </button>
        </div>
      </form>
    );
  }

  if (modalMode !== "pin-settings") return null;

  return (
    <>
      <h2 className={styles.dialogTitle} id="store-partner-dialog-title">삭제 PIN {pinConfigured ? "변경" : "설정"}</h2>
      <p className={styles.dialogDescription}>협력사 삭제를 위해 필요한 비밀번호입니다.</p>
      {pinConfigured ? (
        <>
          <label className={styles.fieldLabel} htmlFor="current-archive-pin">현재 삭제 PIN</label>
          <input
            autoFocus
            className={styles.fieldInput}
            id="current-archive-pin"
            inputMode="numeric"
            maxLength={4}
            onChange={(event) => onCurrentPinChange(event.target.value)}
            type="password"
            value={currentPin}
          />
        </>
      ) : null}
      {!pinConfigured ? (
        <>
          <label className={styles.fieldLabel} htmlFor="archive-account-password">계정 비밀번호 재확인</label>
          <input
            autoFocus
            className={styles.fieldInput}
            id="archive-account-password"
            aria-describedby="archive-account-password-hint"
            onChange={(event) => onAccountPasswordChange(event.target.value)}
            type="password"
            value={accountPassword}
          />
          <p className={styles.fieldHint} id="archive-account-password-hint">
            가입할 때 사용한 비밀번호를 공백 포함 그대로 입력해 주세요.
          </p>
        </>
      ) : null}
      <label className={styles.fieldLabel} htmlFor="new-archive-pin">새 삭제 PIN</label>
      <input
        autoFocus={pinConfigured === true}
        className={styles.fieldInput}
        id="new-archive-pin"
        inputMode="numeric"
        maxLength={4}
        onChange={(event) => onNewPinChange(event.target.value)}
        type="password"
        value={newPin}
      />
      <label className={styles.fieldLabel} htmlFor="new-archive-pin-confirmation">새 삭제 PIN 확인</label>
      <input
        className={styles.fieldInput}
        id="new-archive-pin-confirmation"
        inputMode="numeric"
        maxLength={4}
        onChange={(event) => onNewPinConfirmationChange(event.target.value)}
        type="password"
        value={newPinConfirmation}
      />
      {pinError ? <p className={styles.dialogError} role="alert">{pinError}</p> : null}
      <div className={styles.dialogActions}>
        <button className={styles.dialogCancel} onClick={activePartner ? () => onSetModalMode("detail") : onClose} type="button">취소</button>
        <button className={styles.dialogConfirm} disabled={isSavingPin} onClick={onSavePinSettings} type="button">
          {isSavingPin ? "저장 중..." : "저장"}
        </button>
      </div>
    </>
  );
}
