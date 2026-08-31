targetScope = 'resourceGroup'

@description('Name of the existing Azure Key Vault.')
param vaultName string

@description('Entra object/principal IDs receiving Key Vault Secrets User. Values must be supplied by the operator; no identities are created here.')
param principalIds array

var keyVaultSecretsUserRoleDefinitionId = '4633458b-17de-408a-b874-0445c86b69e6'

resource keyVault 'Microsoft.KeyVault/vaults@2025-05-01' existing = {
  name: vaultName
}

resource keyVaultSecretsUserRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in principalIds: {
  name: guid(vaultName, 'key-vault-secrets-user', principalId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUserRoleDefinitionId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]
