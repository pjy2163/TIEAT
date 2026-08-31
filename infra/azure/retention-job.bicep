targetScope = 'resourceGroup'

@description('Name of the existing Azure Container Apps retention job.')
param jobName string

@description('Resource ID of the existing Azure Container Apps environment.')
param managedEnvironmentId string

@description('API image containing the customer-name anonymization CLI, including an immutable tag or digest.')
param apiImage string

@description('ACR login server for the private API image.')
param acrServer string

@description('Resource ID of the user-assigned identity used only to pull the API image from ACR.')
param acrPullIdentityResourceId string

@description('Resource ID of the user-assigned identity used at runtime for Key Vault, PostgreSQL, and private Blob access.')
param runtimeIdentityResourceId string

@description('Client ID of the runtime identity used by the Azure Blob receipt adapter.')
param runtimeIdentityClientId string

@description('JDBC URL for the production database. Credentials must be supplied separately through Key Vault.')
@secure()
param dbUrl string

@description('Key Vault URI of the DB_USERNAME secret.')
param dbUsernameKeyVaultUrl string

@description('Key Vault URI of the DB_PASSWORD secret.')
@secure()
param dbPasswordKeyVaultUrl string

@description('Key Vault URI of the TIEAT_QR_TOKEN_ENCRYPTION_KEYS secret required by the production profile.')
param qrTokenEncryptionKeysKeyVaultUrl string

@description('Azure Blob Storage endpoint for private receipt storage, without credentials, query strings, or a path.')
param receiptsAzureEndpoint string

@description('Private Azure Blob Storage container name for receipts.')
param receiptsAzureContainer string

@description('Active QR token encryption key version required by the production profile.')
param qrTokenEncryptionKeyVersion string = '1'

@description('Five-field UTC cron expression. 18:00 UTC is 03:00 the next day in Asia/Seoul.')
param cronExpression string = '0 18 * * *'

@description('Maximum seconds allowed for one anonymization execution.')
param replicaTimeout int = 1800

@description('Container CPU allocation.')
param cpu string = '0.25'

@description('Container memory allocation.')
param memory string = '0.5Gi'

resource retentionJob 'Microsoft.App/jobs@2025-01-01' = {
  name: jobName
  location: resourceGroup().location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${acrPullIdentityResourceId}': {}
      '${runtimeIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: managedEnvironmentId
    configuration: {
      triggerType: 'Schedule'
      scheduleTriggerConfig: {
        cronExpression: cronExpression
        parallelism: 1
        replicaCompletionCount: 1
      }
      // Do not hide a failed retention run behind automatic retries or duplicate audit rows.
      replicaRetryLimit: 0
      replicaTimeout: replicaTimeout
      registries: [
        {
          server: acrServer
          identity: acrPullIdentityResourceId
        }
      ]
      secrets: [
        {
          name: 'db-url'
          value: dbUrl
        }
        {
          name: 'db-username'
          keyVaultUrl: dbUsernameKeyVaultUrl
          identity: runtimeIdentityResourceId
        }
        {
          name: 'db-password'
          keyVaultUrl: dbPasswordKeyVaultUrl
          identity: runtimeIdentityResourceId
        }
        {
          name: 'qr-token-encryption-keys'
          keyVaultUrl: qrTokenEncryptionKeysKeyVaultUrl
          identity: runtimeIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'retention'
          image: apiImage
          args: [
            '--spring.profiles.active=retention'
            '--tieat.customer-name-anonymization.command=anonymize'
          ]
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: [
            {
              name: 'DB_URL'
              secretRef: 'db-url'
            }
            {
              name: 'DB_USERNAME'
              secretRef: 'db-username'
            }
            {
              name: 'DB_PASSWORD'
              secretRef: 'db-password'
            }
            {
              name: 'TIEAT_QR_TOKEN_ENCRYPTION_KEYS'
              secretRef: 'qr-token-encryption-keys'
            }
            {
              name: 'TIEAT_QR_TOKEN_ENCRYPTION_KEY_VERSION'
              value: qrTokenEncryptionKeyVersion
            }
            {
              name: 'TIEAT_RECEIPTS_PROVIDER'
              value: 'azure'
            }
            {
              name: 'TIEAT_RECEIPTS_AZURE_ENDPOINT'
              value: receiptsAzureEndpoint
            }
            {
              name: 'TIEAT_RECEIPTS_AZURE_CONTAINER'
              value: receiptsAzureContainer
            }
            {
              name: 'TIEAT_RECEIPTS_AZURE_MANAGED_IDENTITY_CLIENT_ID'
              value: runtimeIdentityClientId
            }
          ]
        }
      ]
    }
  }
}
