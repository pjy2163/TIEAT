targetScope = 'resourceGroup'

@description('Resource ID of the existing Azure Container Apps environment.')
param managedEnvironmentId string

@description('Name of the web Container App.')
param webName string

@description('Name of the API Container App.')
param apiName string

@description('Web container image, including its immutable production tag or digest.')
param webImage string

@description('API container image, including its immutable production tag or digest.')
param apiImage string

@description('Web container CPU allocation.')
param webCpu string = '0.25'

@description('Web container memory allocation.')
param webMemory string = '0.5Gi'

@description('API container CPU allocation.')
param apiCpu string = '0.5'

@description('API container memory allocation.')
param apiMemory string = '1Gi'

@description('ACR login server used by both container apps.')
param acrServer string

@description('Resource ID of the user-assigned identity used only to pull the web image from ACR.')
param webAcrPullIdentityResourceId string

@description('Resource ID of the user-assigned identity used only to pull the API image from ACR. Keep this separate from the API runtime identity.')
param apiAcrPullIdentityResourceId string

@description('Resource ID of the user-assigned identity used by the API at runtime for Azure resources and Key Vault secret references. Keep this separate from the API ACR pull identity.')
param apiRuntimeIdentityResourceId string

@description('Client ID of the API runtime user-assigned identity, passed to the Azure Blob receipt adapter.')
param apiRuntimeIdentityClientId string

@description('Optional comma-separated CIDRs of the trusted reverse proxy path in front of the API. Leave empty until the deployed web-to-API path is verified.')
param apiTrustedProxyCidrs string = ''

@description('JDBC URL for the production database. This must not contain a username or password; credentials are supplied separately through Key Vault.')
@secure()
param dbUrl string

@description('Key Vault URI of the DB_USERNAME secret.')
param dbUsernameKeyVaultUrl string

@description('Key Vault URI of the DB_PASSWORD secret.')
@secure()
param dbPasswordKeyVaultUrl string

@description('Key Vault URI of the TIEAT_QR_TOKEN_ENCRYPTION_KEYS secret.')
param qrTokenEncryptionKeysKeyVaultUrl string

@description('Key Vault URI of the required onboarding invite-code secret. It is required here even though the production configuration treats onboarding settings as optional.')
param onboardingInviteCodeKeyVaultUrl string

@description('Key Vault URI of the NAVER_API_HUB_CLIENT_ID secret. It is required here so the onboarding place-search feature is usable.')
param naverApiHubClientIdKeyVaultUrl string

@description('Key Vault URI of the NAVER_API_HUB_CLIENT_SECRET secret. It is required here so the onboarding place-search feature is usable.')
@secure()
param naverApiHubClientSecretKeyVaultUrl string

@description('Azure Blob Storage endpoint for private receipt storage, without credentials, query strings, or a path.')
param receiptsAzureEndpoint string

@description('Private Azure Blob Storage container name for receipts.')
param receiptsAzureContainer string

@description('Active QR token encryption key version. Must be a positive integer understood by the production validator.')
param qrTokenEncryptionKeyVersion string = '1'

resource web 'Microsoft.App/containerApps@2025-01-01' = {
  name: webName
  location: resourceGroup().location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${webAcrPullIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: managedEnvironmentId
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 3000
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        {
          server: acrServer
          identity: webAcrPullIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'web'
          image: webImage
          resources: {
            cpu: json(webCpu)
            memory: webMemory
          }
        }
      ]
    }
  }
}

resource api 'Microsoft.App/containerApps@2025-01-01' = {
  name: apiName
  location: resourceGroup().location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${apiAcrPullIdentityResourceId}': {}
      '${apiRuntimeIdentityResourceId}': {}
    }
  }
  properties: {
    environmentId: managedEnvironmentId
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        // Internal HTTP is used by the web -> API hop; external=false prevents a public API FQDN.
        // Restrict other environment workloads with ACA/VNet network policy during deployment.
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: true
      }
      registries: [
        {
          server: acrServer
          identity: apiAcrPullIdentityResourceId
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
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'db-password'
          keyVaultUrl: dbPasswordKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'qr-token-encryption-keys'
          keyVaultUrl: qrTokenEncryptionKeysKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'onboarding-invite-code'
          keyVaultUrl: onboardingInviteCodeKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'naver-api-hub-client-id'
          keyVaultUrl: naverApiHubClientIdKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'naver-api-hub-client-secret'
          keyVaultUrl: naverApiHubClientSecretKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'api'
          image: apiImage
          resources: {
            cpu: json(apiCpu)
            memory: apiMemory
          }
          env: concat([
            {
              name: 'SPRING_PROFILES_ACTIVE'
              value: 'production'
            }
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
              name: 'TIEAT_ONBOARDING_INVITE_CODE'
              secretRef: 'onboarding-invite-code'
            }
            {
              name: 'NAVER_API_HUB_CLIENT_ID'
              secretRef: 'naver-api-hub-client-id'
            }
            {
              name: 'NAVER_API_HUB_CLIENT_SECRET'
              secretRef: 'naver-api-hub-client-secret'
            }
            {
              name: 'TIEAT_QR_TOKEN_ENCRYPTION_KEY_VERSION'
              value: qrTokenEncryptionKeyVersion
            }
            {
              name: 'TIEAT_SESSION_COOKIE_SECURE'
              value: 'true'
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
              value: apiRuntimeIdentityClientId
            }
          ], apiTrustedProxyCidrs == '' ? [] : [
            {
              name: 'TIEAT_SECURITY_PUBLIC_QR_CREATE_TRUSTED_PROXY_CIDRS'
              value: apiTrustedProxyCidrs
            }
          ])
        }
      ]
      // The API actuator is protected by SecurityConfiguration and is not an HTTP probe target.
      // Use ACA's default TCP health probes; no custom HTTP actuator probe is defined here.
    }
  }
}
