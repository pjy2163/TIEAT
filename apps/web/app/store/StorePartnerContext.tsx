"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ApiError } from "@/lib/store-api";
import { getStorePartners, getStoreProfile, type StorePartner } from "@/lib/store-partner-api";

const STORE_WORKSPACE_PATHS = new Set([
  "/store/meal-usages",
  "/store/meal-usages/months",
  "/store/pos-settlements",
  "/store/profile",
  "/store/partners/new",
  "/store/qr",
]);

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type RawScope =
  | { kind: "pending" }
  | { kind: "all" }
  | { kind: "selected"; mealContractId: string }
  | { kind: "invalid" };

export type StorePartnerScopeState = "initializing" | "all" | "selected" | "invalid";
export type StorePartnerDirectoryState = "idle" | "loading" | "ready" | "error";

type StorePartnerContextValue = {
  isWorkspaceRoute: boolean;
  isMonthlyLedgerRoute: boolean;
  storeDisplayName: string | null;
  partners: StorePartner[];
  directoryState: StorePartnerDirectoryState;
  refreshDirectory: () => void;
  upsertPartner: (partner: StorePartner) => void;
  scopeState: StorePartnerScopeState;
  scopeKey: string;
  scopeReady: boolean;
  selectedMealContractId: string | null;
  selectPartner: (mealContractId: string | null) => void;
};

const inactiveContext: StorePartnerContextValue = {
  isWorkspaceRoute: false,
  isMonthlyLedgerRoute: false,
  storeDisplayName: null,
  partners: [],
  directoryState: "idle",
  refreshDirectory: () => undefined,
  upsertPartner: () => undefined,
  scopeState: "all",
  scopeKey: "inactive",
  scopeReady: true,
  selectedMealContractId: null,
  selectPartner: () => undefined,
};

const StorePartnerContext = createContext<StorePartnerContextValue>(inactiveContext);

function readRawScope(): RawScope {
  const values = new URLSearchParams(window.location.search).getAll("mealContractId");
  if (values.length === 0) return { kind: "all" };
  if (values.length !== 1 || !UUID_PATTERN.test(values[0])) return { kind: "invalid" };
  return { kind: "selected", mealContractId: values[0].toLowerCase() };
}

export function StorePartnerProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const isWorkspaceRoute = pathname !== null && STORE_WORKSPACE_PATHS.has(pathname);
  const isMonthlyLedgerRoute = pathname === "/store/meal-usages/months";
  const serializedSearchParams = searchParams.toString();
  const [rawScope, setRawScope] = useState<RawScope>({ kind: "pending" });
  const [storeDisplayName, setStoreDisplayName] = useState<string | null>(null);
  const [partners, setPartners] = useState<StorePartner[]>([]);
  const [directoryState, setDirectoryState] = useState<StorePartnerDirectoryState>("idle");
  const directoryRequestedRef = useRef(false);
  const directoryRequestIdRef = useRef(0);

  const loadDirectory = useCallback(() => {
    const requestId = directoryRequestIdRef.current + 1;
    directoryRequestIdRef.current = requestId;
    setDirectoryState("loading");
    void getStorePartners()
      .then((nextPartners) => {
        if (directoryRequestIdRef.current !== requestId) return;
        setPartners(nextPartners);
        setDirectoryState("ready");
      })
      .catch((error: unknown) => {
        if (directoryRequestIdRef.current !== requestId) return;
        setPartners([]);
        setDirectoryState("error");
        if (error instanceof ApiError && error.status === 401) {
          setRawScope({ kind: "all" });
          directoryRequestedRef.current = true;
          router.replace("/store/login?next=/store/meal-usages/months");
        }
      });
  }, [router]);

  useEffect(() => {
    if (!isWorkspaceRoute) {
      setRawScope({ kind: "all" });
      setStoreDisplayName(null);
      directoryRequestIdRef.current += 1;
      directoryRequestedRef.current = false;
      setPartners([]);
      setDirectoryState("idle");
      return;
    }
    const syncFromUrl = () => setRawScope(readRawScope());
    syncFromUrl();
    window.addEventListener("popstate", syncFromUrl);
    return () => window.removeEventListener("popstate", syncFromUrl);
  }, [isWorkspaceRoute, pathname, serializedSearchParams]);

  useEffect(() => {
    if (!isWorkspaceRoute) return;
    let active = true;
    void getStoreProfile()
      .then((profile) => {
        if (active) setStoreDisplayName(profile.storeDisplayName);
      })
      .catch((error: unknown) => {
        if (!active) return;
        setStoreDisplayName(null);
        if (error instanceof ApiError && error.status === 401) {
          router.replace("/store/login?next=/store/meal-usages/months");
        }
      });
    return () => {
      active = false;
    };
  }, [isWorkspaceRoute, router]);

  useEffect(() => {
    if (!isWorkspaceRoute || directoryRequestedRef.current) return;
    directoryRequestedRef.current = true;
    loadDirectory();
  }, [isWorkspaceRoute, loadDirectory]);

  const refreshDirectory = useCallback(() => {
    if (!isWorkspaceRoute) return;
    directoryRequestedRef.current = true;
    loadDirectory();
  }, [isWorkspaceRoute, loadDirectory]);

  const upsertPartner = useCallback((partner: StorePartner) => {
    setPartners((currentPartners) => {
      const nextPartners = currentPartners.filter((item) => item.mealContractId !== partner.mealContractId);
      nextPartners.push(partner);
      nextPartners.sort((left, right) => left.partnerDisplayName.localeCompare(right.partnerDisplayName) || left.mealContractId.localeCompare(right.mealContractId));
      return nextPartners;
    });
    setDirectoryState("ready");
  }, []);

  const selectPartner = useCallback((mealContractId: string | null) => {
    if (!isMonthlyLedgerRoute) return;
    if (mealContractId === null) {
      setRawScope({ kind: "all" });
      router.replace("/store/meal-usages/months");
      return;
    }
    if (!UUID_PATTERN.test(mealContractId) || !partners.some((partner) => partner.mealContractId === mealContractId)) {
      setRawScope({ kind: "invalid" });
      return;
    }
    setRawScope({ kind: "selected", mealContractId });
    router.replace(`/store/meal-usages/months?mealContractId=${encodeURIComponent(mealContractId)}`);
  }, [isMonthlyLedgerRoute, partners, router]);

  const value = useMemo<StorePartnerContextValue>(() => {
    if (!isWorkspaceRoute) return inactiveContext;

    if (rawScope.kind === "pending") {
      return {
        isWorkspaceRoute,
        isMonthlyLedgerRoute,
        storeDisplayName,
        partners,
        directoryState,
        refreshDirectory,
        upsertPartner,
        scopeState: "initializing",
        scopeKey: "pending",
        scopeReady: false,
        selectedMealContractId: null,
        selectPartner,
      };
    }

    if (rawScope.kind === "invalid") {
      return {
        isWorkspaceRoute,
        isMonthlyLedgerRoute,
        storeDisplayName,
        partners,
        directoryState,
        refreshDirectory,
        upsertPartner,
        scopeState: "invalid",
        scopeKey: "invalid",
        scopeReady: true,
        selectedMealContractId: null,
        selectPartner,
      };
    }

    if (rawScope.kind === "all") {
      return {
        isWorkspaceRoute,
        isMonthlyLedgerRoute,
        storeDisplayName,
        partners,
        directoryState,
        refreshDirectory,
        upsertPartner,
        scopeState: "all",
        scopeKey: "all",
        scopeReady: true,
        selectedMealContractId: null,
        selectPartner,
      };
    }

    const partnerExists = directoryState === "ready"
      && partners.some((partner) => partner.mealContractId === rawScope.mealContractId);
    const directoryFailed = directoryState === "error";
    return {
      isWorkspaceRoute,
      isMonthlyLedgerRoute,
      storeDisplayName,
      partners,
      directoryState,
      refreshDirectory,
      upsertPartner,
      scopeState: partnerExists ? "selected" : directoryFailed ? "invalid" : "initializing",
      scopeKey: `selected:${rawScope.mealContractId}`,
      scopeReady: partnerExists || directoryFailed,
      selectedMealContractId: partnerExists ? rawScope.mealContractId : null,
      selectPartner,
    };
  }, [directoryState, isMonthlyLedgerRoute, isWorkspaceRoute, partners, rawScope, refreshDirectory, selectPartner, storeDisplayName, upsertPartner]);

  return <StorePartnerContext.Provider value={value}>{children}</StorePartnerContext.Provider>;
}

export function useStorePartnerContext(): StorePartnerContextValue {
  return useContext(StorePartnerContext);
}
