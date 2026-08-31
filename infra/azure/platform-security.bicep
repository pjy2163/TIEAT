targetScope = 'subscription'

// This contract only adds private endpoints, DNS zone groups, and least-privilege RBAC
// for existing resources. It intentionally does not change publicNetworkAccess. The
// operator must verify private connectivity and DNS before disabling public access on
// the existing services through their service-specific control plane.

@description('Full resource ID of the existing Azure Key Vault. The resource is only referenced; it is not created or updated.')
param keyVaultResourceId string

@description('Full resource ID of the existing Azure Container Registry. The resource is only referenced; it is not created or updated.')
param acrResourceId string

@description('Full resource ID of the existing Azure Storage Account. The resource is only referenced; it is not created or updated.')
param storageAccountResourceId string

@description('Full resource ID of the existing Azure Database for PostgreSQL Flexible Server. The resource is only referenced; it is not created or updated.')
param postgresqlServerResourceId string

@description('Resource ID of the existing subnet reserved for private endpoints.')
param privateEndpointSubnetResourceId string

@description('Name of the existing resource group where the new private endpoints will be created.')
param privateEndpointResourceGroupName string

@description('Azure region for the new private endpoints.')
param privateEndpointLocation string

@description('Resource ID of the existing Private DNS zone for Azure Key Vault, normally privatelink.vaultcore.azure.net.')
param keyVaultPrivateDnsZoneResourceId string

@description('Resource ID of the existing Private DNS zone for Blob Storage, normally privatelink.blob.core.windows.net.')
param blobPrivateDnsZoneResourceId string

@description('Resource ID of the existing Private DNS zone for PostgreSQL Flexible Server, normally privatelink.postgres.database.azure.com.')
param postgresqlPrivateDnsZoneResourceId string

@description('Name of the new private endpoint for the existing Key Vault.')
param keyVaultPrivateEndpointName string

@description('Name of the new private endpoint for the existing Storage Account Blob service.')
param blobPrivateEndpointName string

@description('Name of the new private endpoint for the existing PostgreSQL Flexible Server.')
param postgresqlPrivateEndpointName string

@description('Entra object/principal ID of the user-assigned identity that pulls the web image from ACR; this is not the client ID or resource ID.')
param webAcrPullPrincipalId string

@description('Entra object/principal ID of the user-assigned identity that pulls the API image from ACR; this is not the client ID or resource ID.')
param apiAcrPullPrincipalId string

@description('Entra object/principal ID of the user-assigned identity that pulls the retention job image from ACR; this is not the client ID or resource ID.')
param retentionAcrPullPrincipalId string

@description('Entra object/principal ID of the user-assigned identity used by the API at runtime; this is not the client ID or resource ID.')
param apiRuntimePrincipalId string

@description('Entra object/principal ID of the user-assigned identity used by the retention job at runtime; this is not the client ID or resource ID.')
param retentionRuntimePrincipalId string

var keyVaultIdParts = split(keyVaultResourceId, '/')
var acrIdParts = split(acrResourceId, '/')
var storageAccountIdParts = split(storageAccountResourceId, '/')
var privateEndpointSubnetIdParts = split(privateEndpointSubnetResourceId, '/')

var keyVaultSubscriptionId = keyVaultIdParts[2]
var keyVaultResourceGroupName = keyVaultIdParts[4]
var keyVaultName = last(keyVaultIdParts)

var acrSubscriptionId = acrIdParts[2]
var acrResourceGroupName = acrIdParts[4]
var acrName = last(acrIdParts)

var storageAccountSubscriptionId = storageAccountIdParts[2]
var storageAccountResourceGroupName = storageAccountIdParts[4]
var storageAccountName = last(storageAccountIdParts)

var privateEndpointSubscriptionId = privateEndpointSubnetIdParts[2]

module keyVaultPrivateEndpoint './platform-security.private-endpoint.bicep' = {
  name: 'platform-security-key-vault-private-endpoint'
  scope: resourceGroup(privateEndpointSubscriptionId, privateEndpointResourceGroupName)
  params: {
    endpointName: keyVaultPrivateEndpointName
    location: privateEndpointLocation
    subnetResourceId: privateEndpointSubnetResourceId
    privateLinkServiceResourceId: keyVaultResourceId
    groupId: 'vault'
    privateDnsZoneResourceId: keyVaultPrivateDnsZoneResourceId
  }
}

module blobPrivateEndpoint './platform-security.private-endpoint.bicep' = {
  name: 'platform-security-blob-private-endpoint'
  scope: resourceGroup(privateEndpointSubscriptionId, privateEndpointResourceGroupName)
  params: {
    endpointName: blobPrivateEndpointName
    location: privateEndpointLocation
    subnetResourceId: privateEndpointSubnetResourceId
    privateLinkServiceResourceId: storageAccountResourceId
    groupId: 'blob'
    privateDnsZoneResourceId: blobPrivateDnsZoneResourceId
  }
}

module postgresqlPrivateEndpoint './platform-security.private-endpoint.bicep' = {
  name: 'platform-security-postgresql-private-endpoint'
  scope: resourceGroup(privateEndpointSubscriptionId, privateEndpointResourceGroupName)
  params: {
    endpointName: postgresqlPrivateEndpointName
    location: privateEndpointLocation
    subnetResourceId: privateEndpointSubnetResourceId
    privateLinkServiceResourceId: postgresqlServerResourceId
    groupId: 'postgresqlServer'
    privateDnsZoneResourceId: postgresqlPrivateDnsZoneResourceId
  }
}

module acrRbac './platform-security.acr-rbac.bicep' = {
  name: 'platform-security-acr-rbac'
  scope: resourceGroup(acrSubscriptionId, acrResourceGroupName)
  params: {
    acrName: acrName
    principalIds: [
      webAcrPullPrincipalId
      apiAcrPullPrincipalId
      retentionAcrPullPrincipalId
    ]
  }
}

module keyVaultRbac './platform-security.key-vault-rbac.bicep' = {
  name: 'platform-security-key-vault-rbac'
  scope: resourceGroup(keyVaultSubscriptionId, keyVaultResourceGroupName)
  params: {
    vaultName: keyVaultName
    principalIds: [
      apiRuntimePrincipalId
      retentionRuntimePrincipalId
    ]
  }
}

module storageRbac './platform-security.storage-rbac.bicep' = {
  name: 'platform-security-storage-rbac'
  scope: resourceGroup(storageAccountSubscriptionId, storageAccountResourceGroupName)
  params: {
    storageAccountName: storageAccountName
    principalIds: [
      apiRuntimePrincipalId
      retentionRuntimePrincipalId
    ]
  }
}
