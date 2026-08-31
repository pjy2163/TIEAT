targetScope = 'resourceGroup'

@description('Name of the existing Azure Container Registry.')
param acrName string

@description('Entra object/principal IDs receiving AcrPull. Values must be supplied by the operator; no identities are created here.')
param principalIds array

var acrPullRoleDefinitionId = '7f951dda-4ed3-4680-a7ca-43fe172d538d'

resource acr 'Microsoft.ContainerRegistry/registries@2025-04-01' existing = {
  name: acrName
}

resource acrPullRoleAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in principalIds: {
  name: guid(acrName, 'acr-pull', principalId)
  scope: acr
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPullRoleDefinitionId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]
