targetScope = 'resourceGroup'

@description('Name of the existing Azure Storage Account.')
param storageAccountName string

@description('Entra object/principal IDs receiving Storage Blob Data Contributor. Values must be supplied by the operator; no identities are created here.')
param principalIds array

var blobDataContributorRoleDefinitionId = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'

resource storageAccount 'Microsoft.Storage/storageAccounts@2025-06-01' existing = {
  name: storageAccountName
}

resource blobDataContributorRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in principalIds: {
  name: guid(storageAccountName, 'storage-blob-data-contributor', principalId)
  scope: storageAccount
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobDataContributorRoleDefinitionId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]
