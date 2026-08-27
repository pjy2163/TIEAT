"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { TieatWordmark } from "../TieatWordmark";
import { filterPartnersByKind, type PartnerKindFilter } from "@/lib/partner-kind";
import { useStorePartnerContext } from "./StorePartnerContext";
import { StorePartnerKindFilter } from "./StorePartnerKindFilter";
import { StoreBackLink } from "./StoreBackLink";
import { StoreCloseButton } from "./StoreCloseButton";
import { storeWorkspaceShellStyles as styles } from "./StoreWorkspaceShell.styles";

const ledgerPath = "/store/meal-usages/months";

type NavigationProps = {
  onNavigate?: () => void;
  storeDisplayName: string | null;
};

function partnerLedgerHref(mealContractId: string): string {
  return `${ledgerPath}?mealContractId=${encodeURIComponent(mealContractId)}`;
}

function WorkspaceBrand({ mobile = false }: Readonly<{ mobile?: boolean }>) {
  return (
    <Link
      aria-label="TIEAT 매장 홈"
      className={mobile ? styles.mobileBrand : styles.brand}
      href="/store/meal-usages"
    >
      <TieatWordmark
        className={mobile ? styles.mobileWordmark : styles.brandWordmark}
        markId="header"
        variant="header"
      />
    </Link>
  );
}

function isActivePath(pathname: string | null, target: string): boolean {
  return pathname === target;
}

function Navigation({ onNavigate, storeDisplayName }: NavigationProps) {
  const pathname = usePathname();
  const {
    partners,
    directoryState,
    selectedMealContractId,
    refreshDirectory,
  } = useStorePartnerContext();
  const [partnerKindFilter, setPartnerKindFilter] = useState<PartnerKindFilter>("ALL");
  const visiblePartners = filterPartnersByKind(partners, partnerKindFilter);
  const allLedgerActive = pathname === ledgerPath && selectedMealContractId === null;

  function linkClassName(active: boolean): string {
    return `${styles.navLink} ${active ? styles.navLinkActive : ""}`;
  }

  function partnerClassName(active: boolean): string {
    return `${styles.directoryLink} ${active ? styles.directoryLinkActive : ""}`;
  }

  return (
    <nav aria-label="매장 작업 공간 메뉴" className={styles.navigation}>
      <p className={styles.storeName}>{storeDisplayName ?? "매장"}</p>
      <div className={styles.nav}>
        <Link aria-current={isActivePath(pathname, "/store/meal-usages") ? "page" : undefined} className={linkClassName(isActivePath(pathname, "/store/meal-usages"))} href="/store/meal-usages" onClick={onNavigate}>확인 대기</Link>
        <Link aria-current={allLedgerActive ? "page" : undefined} className={linkClassName(allLedgerActive)} href={ledgerPath} onClick={onNavigate}>전체 장부</Link>
        <Link aria-current={isActivePath(pathname, "/store/pos-settlements") ? "page" : undefined} className={linkClassName(isActivePath(pathname, "/store/pos-settlements"))} href="/store/pos-settlements" onClick={onNavigate}>결제 내역</Link>
        <Link aria-current={isActivePath(pathname, "/store/profile") ? "page" : undefined} className={linkClassName(isActivePath(pathname, "/store/profile"))} href="/store/profile" onClick={onNavigate}>마이페이지</Link>
      </div>

      <div className={styles.directory}>
        <p className={styles.navLabel}>협력사</p>
        <StorePartnerKindFilter
          ariaLabel="협력사 유형 필터"
          className={styles.kindFilter}
          onChange={setPartnerKindFilter}
          value={partnerKindFilter}
        />
        {directoryState === "loading" ? <p className={styles.directoryStatus} role="status">협력사 목록을 불러오는 중입니다.</p> : null}
        {directoryState === "error" ? (
          <div className={styles.directoryError} role="alert">
            <span>협력사 목록을 불러오지 못했습니다.</span>
            <button className={styles.retryButton} onClick={refreshDirectory} type="button">다시 시도</button>
          </div>
        ) : null}
        {directoryState === "ready" && visiblePartners.length === 0 ? <p className={styles.directoryStatus}>해당 유형 협력사가 없습니다.</p> : null}
        {directoryState === "ready" ? visiblePartners.map((partner) => (
          <Link
            aria-current={pathname === ledgerPath && selectedMealContractId === partner.mealContractId ? "page" : undefined}
            className={partnerClassName(pathname === ledgerPath && selectedMealContractId === partner.mealContractId)}
            href={partnerLedgerHref(partner.mealContractId)}
            key={partner.mealContractId}
            onClick={onNavigate}
            title={partner.partnerDisplayName}
          >
            <span className={styles.directoryName}>{partner.partnerDisplayName}</span>
          </Link>
        )) : null}
      </div>

      <div className={styles.footerLinks}>
        <Link className={styles.addLink} href="/store/partners/new" onClick={onNavigate}>+ 협력사 추가</Link>
        <Link
          aria-current={isActivePath(pathname, "/store/qr") ? "page" : undefined}
          className={styles.qrLink}
          href="/store/qr"
          onClick={onNavigate}
        >
          QR코드 보기
        </Link>
      </div>
    </nav>
  );
}

export function StoreWorkspaceShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const { isWorkspaceRoute, storeDisplayName } = useStorePartnerContext();
  const pathname = usePathname();
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const onClose = () => {
      setIsDrawerOpen(false);
      menuButtonRef.current?.focus();
    };
    dialog.addEventListener("close", onClose);
    return () => dialog.removeEventListener("close", onClose);
  }, []);

  if (!isWorkspaceRoute) return children;

  function openDrawer() {
    const dialog = dialogRef.current;
    if (!dialog || dialog.open) return;
    dialog.showModal();
    setIsDrawerOpen(true);
    requestAnimationFrame(() => closeButtonRef.current?.focus());
  }

  function closeDrawer() {
    const dialog = dialogRef.current;
    if (dialog?.open) dialog.close();
    setIsDrawerOpen(false);
  }

  return (
    <div className={styles.workspace}>
      <aside className={styles.desktopSidebar} aria-label="매장 작업 공간">
        <div className={styles.sidebarInner}>
          <WorkspaceBrand />
          <p className={styles.brandDescription}>자동으로 기록이 쌓이는 장부</p>
          <Navigation storeDisplayName={storeDisplayName} />
        </div>
      </aside>

      <div className={styles.content}>
        <header className={styles.mobileHeader}>
          <WorkspaceBrand mobile />
          <button
            aria-controls="store-workspace-drawer"
            aria-expanded={isDrawerOpen}
            className={styles.menuButton}
            onClick={openDrawer}
            ref={menuButtonRef}
            type="button"
          >
            메뉴
          </button>
        </header>

        {pathname !== "/store/meal-usages" ? (
          <div className={styles.pageBack}>
            <StoreBackLink href="/store/meal-usages" />
          </div>
        ) : null}
        {children}

        <dialog
          aria-label="매장 작업 공간 메뉴"
          className={styles.drawer}
          id="store-workspace-drawer"
          onKeyDown={(event) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            closeDrawer();
          }}
          onCancel={(event) => {
            event.preventDefault();
            closeDrawer();
          }}
          onClick={(event) => {
            if (event.target === event.currentTarget) closeDrawer();
          }}
          ref={dialogRef}
        >
          <div className={styles.drawerPanel} onClick={(event) => event.stopPropagation()}>
            <div className={styles.drawerHeader}>
              <p className={styles.drawerTitle}>매장 메뉴</p>
              <StoreCloseButton onClick={closeDrawer} ref={closeButtonRef} />
            </div>
            <div className="mt-6 flex min-h-0 flex-1 flex-col">
              <Navigation onNavigate={closeDrawer} storeDisplayName={storeDisplayName} />
            </div>
          </div>
        </dialog>
      </div>
    </div>
  );
}
