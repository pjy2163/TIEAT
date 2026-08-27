const STORE_AUTHENTICATED_PATHS = [
  "/store/meal-usages",
  "/store/meal-usages/months",
  "/store/pos-settlements",
  "/store/profile",
  "/store/partners/new",
  "/store/qr",
  "/store/onboarding/partner",
] as const;

type StoreAuthenticatedPath = typeof STORE_AUTHENTICATED_PATHS[number];

function isStoreAuthenticatedPath(value: string): value is StoreAuthenticatedPath {
  return STORE_AUTHENTICATED_PATHS.some((path) => path === value);
}

export function safeStoreNext(value: string | null): string {
  return value !== null && isStoreAuthenticatedPath(value) ? value : "/store/meal-usages";
}

export function hasSafeStoreNext(value: string | null): boolean {
  return value !== null && isStoreAuthenticatedPath(value);
}

export function storeLoginRedirect(pathname: string | null): string {
  return `/store/login?next=${safeStoreNext(pathname)}`;
}
