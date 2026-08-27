import Link from "next/link";
import { filterPartnersByKind, type PartnerKindFilter } from "@/lib/partner-kind";
import type { StorePartner, StoreProfile } from "@/lib/store-partner-api";
import type { StorePartnerDirectoryState } from "../StorePartnerContext";
import { StorePartnerKindFilter } from "../StorePartnerKindFilter";
import { storeProfileStyles as styles } from "./StoreProfileView.styles";
import { ledgerHref } from "./StoreProfileView.helpers";
import { StoreLogoutButton } from "./StoreLogoutButton";

type StoreProfileOverviewProps = Readonly<{
  profile: StoreProfile;
  partners: StorePartner[];
  directoryState: StorePartnerDirectoryState;
  partnerKindFilter: PartnerKindFilter;
  onPartnerKindFilterChange: (filter: PartnerKindFilter) => void;
  onRefreshDirectory: () => void;
  onOpenDetail: (partner: StorePartner) => void;
}>;

export function StoreProfileOverview({
  profile,
  partners,
  directoryState,
  partnerKindFilter,
  onPartnerKindFilterChange,
  onRefreshDirectory,
  onOpenDetail,
}: StoreProfileOverviewProps) {
  const visiblePartners = filterPartnersByKind(partners, partnerKindFilter);

  return (
    <>
      <p className={styles.eyebrow}>TIEAT STORE</p>
      <h1 className={styles.title} id="store-profile-title">마이페이지</h1>
      <p className={styles.description}>매장 정보와 현재 연결된 협력사를 확인할 수 있습니다.</p>

      <div className={styles.grid}>
        <section className={styles.card} aria-labelledby="store-info-title">
          <h2 className={styles.cardTitle} id="store-info-title">매장 정보</h2>
          <dl className={styles.details}>
            <div>
              <dt className={styles.detailLabel}>매장 이름</dt>
              <dd className={styles.detailValue}>{profile.storeDisplayName ?? "매장 이름 미등록"}</dd>
            </div>
            <div>
              <dt className={styles.detailLabel}>로그인 ID</dt>
              <dd className={styles.detailValue}>{profile.loginId}</dd>
            </div>
          </dl>
          <StoreLogoutButton />
        </section>

        <section className={styles.card} aria-labelledby="store-partners-title">
          <h2 className={styles.cardTitle} id="store-partners-title">협력사 목록</h2>
          <p className={styles.cardDescription}>협력사별 장부와 상세 정보를 확인하세요.</p>
          <StorePartnerKindFilter
            className={styles.kindFilter}
            disabled={directoryState !== "ready"}
            onChange={onPartnerKindFilterChange}
            value={partnerKindFilter}
          />
          {directoryState === "loading" || directoryState === "idle" ? <p className={styles.notice} role="status">협력사 목록을 불러오는 중입니다.</p> : null}
          {directoryState === "error" ? (
            <div className={styles.error} role="alert">
              협력사 목록을 불러오지 못했습니다.
              <button className={`${styles.stateAction} mt-3`} onClick={onRefreshDirectory} type="button">다시 시도</button>
            </div>
          ) : null}
          {directoryState === "ready" && partners.length === 0 ? <p className={styles.notice}>등록된 협력사가 없습니다.</p> : null}
          {directoryState === "ready" && partners.length > 0 && visiblePartners.length === 0 ? <p className={styles.notice}>해당 유형 협력사가 없습니다.</p> : null}
          {directoryState === "ready" && visiblePartners.length > 0 ? (
            <ul className={styles.partnerList}>
              {visiblePartners.map((partner) => (
                <li className={styles.partnerItem} key={partner.mealContractId}>
                  <button className={styles.partnerNameButton} onClick={() => onOpenDetail(partner)} type="button">
                    {partner.partnerDisplayName}
                  </button>
                  <div className={styles.partnerActions}>
                    <Link className={styles.partnerLink} href={ledgerHref(partner.mealContractId)}>장부 보기</Link>
                    <button className={styles.detailButton} onClick={() => onOpenDetail(partner)} type="button">상세</button>
                  </div>
                </li>
              ))}
            </ul>
          ) : null}
          <Link className={styles.addLink} href="/store/partners/new">+ 협력사 추가</Link>
        </section>
      </div>
    </>
  );
}
