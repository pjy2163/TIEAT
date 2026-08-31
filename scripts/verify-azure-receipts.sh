#!/usr/bin/env bash
set -uo pipefail

required_environment=(
  TIEAT_RECEIPTS_AZURE_RESOURCE_GROUP
  TIEAT_RECEIPTS_AZURE_ACCOUNT
  TIEAT_RECEIPTS_AZURE_CONTAINER
)

for variable_name in "${required_environment[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "FAIL: ${variable_name} must be set."
    exit 2
  fi
done

expected_sku="${TIEAT_RECEIPTS_AZURE_EXPECTED_SKU:-Standard_LRS}"

if ! command -v az >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: Azure CLI (az) and jq are required."
  exit 2
fi

evidence_dir="$(mktemp -d)" || {
  echo "FAIL: temporary evidence directory could not be created."
  exit 2
}
trap 'rm -rf "$evidence_dir"' EXIT

read_json() {
  local destination="$1"
  shift
  if ! az "$@" --only-show-errors --output json >"$destination" 2>/dev/null; then
    echo "FAIL: Azure resource properties could not be read."
    return 2
  fi
}

account_json="$evidence_dir/account.json"
blob_service_json="$evidence_dir/blob-service.json"
container_json="$evidence_dir/container.json"

read_json "$account_json" storage account show \
  --resource-group "$TIEAT_RECEIPTS_AZURE_RESOURCE_GROUP" \
  --name "$TIEAT_RECEIPTS_AZURE_ACCOUNT" \
  --query '{sku:sku.name,allowBlobPublicAccess:allowBlobPublicAccess}' || exit $?

read_json "$blob_service_json" storage account blob-service-properties show \
  --resource-group "$TIEAT_RECEIPTS_AZURE_RESOURCE_GROUP" \
  --account-name "$TIEAT_RECEIPTS_AZURE_ACCOUNT" \
  --query '{versioning:isVersioningEnabled,blobSoftDelete:deleteRetentionPolicy.enabled,containerSoftDelete:containerDeleteRetentionPolicy.enabled}' || exit $?

read_json "$container_json" storage container show \
  --account-name "$TIEAT_RECEIPTS_AZURE_ACCOUNT" \
  --name "$TIEAT_RECEIPTS_AZURE_CONTAINER" \
  --auth-mode login \
  --query '{publicAccess:properties.publicAccess,hasImmutabilityPolicy:properties.hasImmutabilityPolicy,hasLegalHold:properties.hasLegalHold}' || exit $?

failed=0

if jq -e --arg expected_sku "$expected_sku" '
    (.sku | type == "string")
    and (.sku == $expected_sku)
    and (.allowBlobPublicAccess | type == "boolean")
    and (.allowBlobPublicAccess == false)
  ' "$account_json" >/dev/null 2>&1; then
  echo "PASS: account SKU and public access policy match the receipt contract."
else
  echo "FAIL: account SKU or public access policy does not match the receipt contract."
  failed=1
fi

if jq -e '
    (.versioning | type == "boolean")
    and (.versioning == false)
    and (.blobSoftDelete | type == "boolean")
    and (.blobSoftDelete == false)
    and (.containerSoftDelete | type == "boolean")
    and (.containerSoftDelete == false)
  ' "$blob_service_json" >/dev/null 2>&1; then
  echo "PASS: Blob versioning and soft-delete policies are disabled."
else
  echo "FAIL: Blob versioning or soft-delete policies are enabled or unreadable."
  failed=1
fi

if jq -e '
    (.publicAccess | type == "string")
    and ((.publicAccess | ascii_downcase) == "none")
    and (.hasImmutabilityPolicy | type == "boolean")
    and (.hasImmutabilityPolicy == false)
    and (.hasLegalHold | type == "boolean")
    and (.hasLegalHold == false)
  ' "$container_json" >/dev/null 2>&1; then
  echo "PASS: container public access, immutability, and legal hold are disabled."
else
  echo "FAIL: container access or recovery-copy policies do not match the receipt contract."
  failed=1
fi

if ((failed != 0)); then
  echo "Azure receipt storage preflight failed. Runtime must not use the Azure provider."
  exit 1
fi

echo "PASS: Azure receipt storage properties match the machine-checkable contract."
echo "MANUAL GATE: separately verify Azure Backup/replication control-plane evidence before deployment."
echo "Azure receipt deployment remains blocked until the manual gate is recorded."
exit 1
