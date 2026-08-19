"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  type StorePlaceSearchResult,
  searchStorePlaces,
  signUpStoreAccount,
} from "@/lib/store-api";
import { signupStyles } from "./SignupForm.styles";

type SignupStep = "account" | "store";

export function SignupForm() {
  const router = useRouter();
  const [step, setStep] = useState<SignupStep>("account");
  const [inviteCode, setInviteCode] = useState("");
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [placeQuery, setPlaceQuery] = useState("");
  const [placeEntries, setPlaceEntries] = useState<StorePlaceSearchResult[]>([]);
  const [selectedPlace, setSelectedPlace] = useState<StorePlaceSearchResult | null>(null);
  const [useManualName, setUseManualName] = useState(false);
  const [manualStoreName, setManualStoreName] = useState("");
  const [hasSearched, setHasSearched] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  function continueToStore(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setStep("store");
  }

  async function searchPlaces() {
    const query = placeQuery.trim();
    if (query.length < 2) {
      setErrorMessage("가게명을 두 글자 이상 입력해 주세요.");
      return;
    }

    setIsSearching(true);
    setErrorMessage(null);
    setSelectedPlace(null);
    setHasSearched(true);
    try {
      const entries = await searchStorePlaces({ inviteCode, query });
      setPlaceEntries(entries);
      setUseManualName(entries.length === 0);
      if (entries.length > 0) {
        setManualStoreName("");
      }
    } catch (error) {
      setPlaceEntries([]);
      setUseManualName(true);
      setHasSearched(false);
      if (error instanceof ApiError && error.errorCode === "ONBOARDING_INVITE_INVALID") {
        setErrorMessage("초대 코드를 확인해 주세요.");
      } else {
        setErrorMessage("장소 검색을 사용할 수 없습니다. 가게명을 직접 입력해 주세요.");
      }
    } finally {
      setIsSearching(false);
    }
  }

  async function submitStore(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedPlace && !manualStoreName.trim()) {
      setErrorMessage("검색 결과를 선택하거나 새 가게명을 입력해 주세요.");
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    setSuccessMessage(null);
    try {
      await signUpStoreAccount({
        inviteCode,
        loginId,
        password,
        manualStoreName: selectedPlace?.storeDisplayName ?? manualStoreName,
      });
      setSuccessMessage("매장 계정을 만들었습니다. 첫 협력사를 등록해 주세요.");
      router.replace("/store/onboarding/partner");
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "ONBOARDING_INVITE_INVALID") {
        setErrorMessage("초대 코드를 확인해 주세요.");
      } else if (error instanceof ApiError && error.errorCode === "ONBOARDING_LOGIN_ID_IN_USE") {
        setErrorMessage("이미 사용 중인 로그인 ID입니다. 기존 계정으로 로그인해 주세요.");
      } else if (error instanceof ApiError && error.status === 400) {
        setErrorMessage("입력 내용을 확인해 주세요.");
      } else {
        setErrorMessage("계정 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className={signupStyles.page}>
      <section className={signupStyles.card} aria-labelledby="signup-title">
        <p className={signupStyles.eyebrow}>TIEAT STORE</p>
        <h1 id="signup-title" className={signupStyles.title}>매장 계정 만들기</h1>
        <p className={signupStyles.description}>계정과 가게를 등록한 뒤 첫 협력사 설정까지 이어집니다.</p>
        <div className={signupStyles.step} aria-label="가입 단계">
          <span className={step === "account" ? signupStyles.stepCurrent : signupStyles.stepPending}>1. 계정</span>
          <span aria-hidden="true">→</span>
          <span className={step === "store" ? signupStyles.stepCurrent : signupStyles.stepPending}>2. 가게</span>
        </div>

        {step === "account" ? (
          <form className={signupStyles.form} onSubmit={continueToStore}>
            <div className={signupStyles.field}>
              <label className={signupStyles.label} htmlFor="inviteCode">초대 코드</label>
              <input
                className={signupStyles.input}
                id="inviteCode"
                name="inviteCode"
                value={inviteCode}
                onChange={(event) => setInviteCode(event.target.value)}
                autoComplete="off"
                required
              />
              <p className={signupStyles.hint}>파일럿 매장에 전달된 코드입니다.</p>
            </div>
            <div className={signupStyles.field}>
              <label className={signupStyles.label} htmlFor="signupLoginId">로그인 ID</label>
              <input
                className={signupStyles.input}
                id="signupLoginId"
                name="loginId"
                value={loginId}
                onChange={(event) => setLoginId(event.target.value)}
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                minLength={4}
                maxLength={120}
                pattern="[a-z0-9._-]+"
                required
              />
              <p className={signupStyles.hint}>영문 소문자, 숫자, 점(`.`), 밑줄(`_`), 하이픈(`-`)만 사용할 수 있습니다.</p>
            </div>
            <div className={signupStyles.field}>
              <label className={signupStyles.label} htmlFor="signupPassword">비밀번호</label>
              <input
                className={signupStyles.input}
                id="signupPassword"
                name="password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
                minLength={10}
                required
              />
              <p className={signupStyles.hint}>10자 이상으로 입력해 주세요.</p>
            </div>
            {errorMessage ? <p className={signupStyles.error} role="alert">{errorMessage}</p> : null}
            <div className={signupStyles.actions}>
              <span />
              <button className={signupStyles.button} type="submit">가게 설정으로</button>
            </div>
          </form>
        ) : (
          <form className={signupStyles.form} onSubmit={submitStore}>
            <div className={signupStyles.field}>
              <label className={signupStyles.label} htmlFor="storePlaceQuery">가게명 검색</label>
              <div className={signupStyles.searchRow}>
                <input
                  className={signupStyles.input}
                  id="storePlaceQuery"
                  value={placeQuery}
                  onChange={(event) => setPlaceQuery(event.target.value)}
                  placeholder="예: TIEAT 강남점"
                  autoComplete="organization"
                />
                <button className={signupStyles.searchButton} type="button" onClick={searchPlaces} disabled={isSearching}>
                  {isSearching ? "검색 중…" : "검색"}
                </button>
              </div>
              <p className={signupStyles.hint}>카카오 장소 검색으로 상호·주소·업종을 확인합니다. 선택한 상호명만 새 TIEAT 매장명으로 등록합니다.</p>
            </div>

            {placeEntries.length > 0 ? (
              <div className={signupStyles.resultList} role="list" aria-label="가게 검색 결과">
                {placeEntries.map((entry) => (
                  <button
                    className={signupStyles.result}
                    type="button"
                    key={entry.placeId}
                    aria-pressed={selectedPlace?.placeId === entry.placeId}
                    onClick={() => {
                      setSelectedPlace(entry);
                      setUseManualName(false);
                      setManualStoreName("");
                    }}
                    >
                    <span className="min-w-0">
                      <span className={`${signupStyles.resultName} block truncate`}>{entry.storeDisplayName}</span>
                      <span className={`${signupStyles.resultMeta} block truncate`}>
                        {entry.address ?? "주소 정보 없음"}{entry.category ? ` · ${entry.category}` : ""}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
            ) : null}

            {hasSearched && !isSearching && placeEntries.length === 0 ? (
              <p className={signupStyles.empty}>검색 결과가 없습니다. 새 가게명을 직접 입력해 등록할 수 있습니다.</p>
            ) : null}

            <div className={signupStyles.field}>
              {!useManualName && !selectedPlace ? (
                <button className={signupStyles.secondaryButton} type="button" onClick={() => setUseManualName(true)}>
                  가게명 직접 입력
                </button>
              ) : null}
              {useManualName ? (
                <>
                  <label className={signupStyles.label} htmlFor="manualStoreName">가게명 직접 입력</label>
                  <input
                    className={signupStyles.input}
                    id="manualStoreName"
                    value={manualStoreName}
                    onChange={(event) => {
                      setManualStoreName(event.target.value);
                      setSelectedPlace(null);
                    }}
                    maxLength={100}
                    required
                  />
                </>
              ) : null}
            </div>

            {errorMessage ? <p className={signupStyles.error} role="alert">{errorMessage}</p> : null}
            {successMessage ? <p className={signupStyles.success} role="status">{successMessage}</p> : null}
            <div className={signupStyles.actions}>
              <button className={signupStyles.backButton} type="button" onClick={() => setStep("account")} disabled={isSubmitting}>
                이전
              </button>
              <button className={signupStyles.button} type="submit" disabled={isSubmitting}>
                {isSubmitting ? "계정 만드는 중…" : "계정 만들기"}
              </button>
            </div>
          </form>
        )}

        <p className={signupStyles.loginPrompt}>
          이미 계정이 있으신가요? <a className={signupStyles.loginLink} href="/store/login">로그인</a>
        </p>
      </section>
    </main>
  );
}
