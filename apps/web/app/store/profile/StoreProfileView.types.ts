export type StoreProfileModalMode = "detail" | "payment" | "archive-warning" | "archive-pin" | "archive-reauth" | "pin-settings";

export type ArchiveRetrySnapshot = Readonly<{ mealContractId: string; pin: string }>;
