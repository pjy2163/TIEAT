targetScope = 'resourceGroup'

@description('Name of the existing Azure Storage Account.')
param storageAccountName string

@description('Entra object/principal IDs receiving Storage Blob Data Contributor. Values must be supplied by the operator; no identities are created here.')
param principalIds array

@description('Full resource ID of the TIEAT receipt blob-tags reader custom role created by the parent security deployment.')
param blobTagsReaderRoleDefinitionResourceId string

var blobDataContributorRoleDefinitionId = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
var uniquePrincipalIds = union([], principalIds)

resource storageAccount 'Microsoft.Storage/storageAccounts@2025-06-01' existing = {
  name: storageAccountName
}

resource blobDataContributorRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in uniquePrincipalIds: {
  name: guid(storageAccountName, 'storage-blob-data-contributor', principalId)
  scope: storageAccount
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobDataContributorRoleDefinitionId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource blobTagsReaderRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in uniquePrincipalIds: {
  name: guid(storageAccountName, 'storage-blob-tags-reader', principalId)
  scope: storageAccount
  properties: {
    roleDefinitionId: blobTagsReaderRoleDefinitionResourceId
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]
