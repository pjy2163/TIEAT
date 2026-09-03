targetScope = 'resourceGroup'

@description('Region for the approved single-region pilot.')
param location string = resourceGroup().location

@description('Initial database administrator. Do not use this role as the application login.')
param postgresAdminLogin string = 'tieat_admin'

@secure()
@description('Generated administrator password supplied from an operator-only parameter file.')
param postgresAdminPassword string

var suffix = uniqueString(resourceGroup().id)
var tags = {
  application: 'tieat'
  environment: 'pilot'
  managedBy: 'bicep'
}

resource network 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: 'vnet-tieat-pilot'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: ['10.42.0.0/24']
    }
    subnets: [
      {
        name: 'container-apps'
        properties: {
          addressPrefix: '10.42.0.0/27'
          delegations: [{
            name: 'container-apps'
            properties: {serviceName: 'Microsoft.App/environments'}
          }]
        }
      }
      {
        name: 'postgresql'
        properties: {
          addressPrefix: '10.42.0.32/28'
          delegations: [{
            name: 'postgresql'
            properties: {serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'}
          }]
        }
      }
    ]
  }
}

resource postgresDns 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: 'tieat.postgres.database.azure.com'
  location: 'global'
  tags: tags
}

resource postgresDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: postgresDns
  name: 'tieat-pilot'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {id: network.id}
  }
}

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: 'psql-tieat-${suffix}'
  location: location
  tags: tags
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '16'
    administratorLogin: postgresAdminLogin
    administratorLoginPassword: postgresAdminPassword
    storage: {
      storageSizeGB: 32
      autoGrow: 'Disabled'
      type: 'Premium_LRS'
      tier: 'P4'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {mode: 'Disabled'}
    network: {
      publicNetworkAccess: 'Disabled'
      delegatedSubnetResourceId: network.properties.subnets[1].id
      privateDnsZoneArmResourceId: postgresDns.id
    }
  }
  dependsOn: [postgresDnsLink]
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: postgres
  name: 'tieat'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-tieat-pilot'
  location: location
  tags: tags
  properties: {
    sku: {name: 'PerGB2018'}
    retentionInDays: 31
    workspaceCapping: {dailyQuotaGb: json('0.1')}
  }
}

resource environment 'Microsoft.App/managedEnvironments@2025-01-01' = {
  name: 'cae-tieat-pilot'
  location: location
  tags: tags
  properties: {
    zoneRedundant: false
    workloadProfiles: [{
      name: 'Consumption'
      workloadProfileType: 'Consumption'
    }]
    vnetConfiguration: {
      infrastructureSubnetId: network.properties.subnets[0].id
      internal: false
    }
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' = {
  name: 'tieat${suffix}'
  location: location
  tags: tags
  sku: {name: 'Basic'}
  properties: {
    adminUserEnabled: false
    anonymousPullEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource receipts 'Microsoft.Storage/storageAccounts@2025-06-01' = {
  name: 'sttieat${suffix}'
  location: location
  tags: tags
  kind: 'StorageV2'
  sku: {name: 'Standard_LRS'}
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    publicNetworkAccess: 'Enabled'
  }
}

resource receiptService 'Microsoft.Storage/storageAccounts/blobServices@2025-06-01' = {
  parent: receipts
  name: 'default'
  properties: {
    isVersioningEnabled: false
    deleteRetentionPolicy: {enabled: false}
    containerDeleteRetentionPolicy: {enabled: false}
  }
}

resource receiptContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' = {
  parent: receiptService
  name: 'tieat-receipts-private'
  properties: {publicAccess: 'None'}
}

resource vault 'Microsoft.KeyVault/vaults@2025-05-01' = {
  name: 'kv-tieat-${suffix}'
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {family: 'A', name: 'standard'}
    enableRbacAuthorization: true
    enableSoftDelete: true
    enablePurgeProtection: true
    softDeleteRetentionInDays: 7
    publicNetworkAccess: 'Enabled'
    accessPolicies: []
  }
}

var identityNames = ['web-pull', 'api-pull', 'retention-pull', 'api-runtime', 'retention-runtime']
resource identities 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for identityName in identityNames: {
  name: 'id-tieat-${identityName}'
  location: location
  tags: tags
}]

module registryRbac './platform-security.acr-rbac.bicep' = {
  name: 'foundation-acr-rbac'
  params: {
    acrName: registry.name
    principalIds: [
      identities[0].properties.principalId
      identities[1].properties.principalId
      identities[2].properties.principalId
    ]
  }
}

module vaultRbac './platform-security.key-vault-rbac.bicep' = {
  name: 'foundation-vault-rbac'
  params: {
    vaultName: vault.name
    principalIds: [identities[3].properties.principalId, identities[4].properties.principalId]
  }
}

module receiptRbac './platform-security.storage-rbac.bicep' = {
  name: 'foundation-receipts-rbac'
  params: {
    storageAccountName: receipts.name
    principalIds: [identities[3].properties.principalId, identities[4].properties.principalId]
  }
}

output managedEnvironmentId string = environment.id
output acrServer string = registry.properties.loginServer
output keyVaultUri string = vault.properties.vaultUri
output postgresHost string = postgres.properties.fullyQualifiedDomainName
output databaseName string = database.name
output receiptsAzureEndpoint string = receipts.properties.primaryEndpoints.blob
output receiptsAzureContainer string = receiptContainer.name
output workloadIdentities array = [for (identityName, i) in identityNames: {
  name: identityName
  resourceId: identities[i].id
  clientId: identities[i].properties.clientId
  principalId: identities[i].properties.principalId
}]
