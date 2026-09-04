targetScope = 'resourceGroup'

@description('Resource ID of the existing Azure Container Apps environment.')
param managedEnvironmentId string

@description('Name of the web Container App.')
param webName string

@description('Whether the web Container App ingress is publicly reachable.')
param webExternalIngress bool = false

@description('CIDR range allowed to reach the externally exposed web ingress.')
param webAllowedIpCidr string = ''

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

@description('Optional Key Vault URI of the onboarding invite-code secret. Leave empty to disable onboarding invites.')
param onboardingInviteCodeKeyVaultUrl string = ''

@description('Optional Key Vault URI of the NAVER_API_HUB_CLIENT_ID secret. Both Naver URLs must be set to enable place search.')
param naverApiHubClientIdKeyVaultUrl string = ''

@description('Optional Key Vault URI of the NAVER_API_HUB_CLIENT_SECRET secret. Both Naver URLs must be set to enable place search.')
@secure()
param naverApiHubClientSecretKeyVaultUrl string = ''

@description('Azure Blob Storage endpoint for private receipt storage, without credentials, query strings, or a path.')
param receiptsAzureEndpoint string

@description('Private Azure Blob Storage container name for receipts.')
param receiptsAzureContainer string

@description('Active QR token encryption key version. Must be a positive integer understood by the production validator.')
param qrTokenEncryptionKeyVersion string = '1'

@description('Initial floor for this deployment. Pass the current scheduled value on redeploy: 1 during service hours, 0 overnight. scale-schedule.bicep changes the actual floor thereafter.')
@allowed([0, 1])
param initialMinReplicas int = 1

var validatedWebAllowedIpCidr = webExternalIngress
  ? (!empty(webAllowedIpCidr) ? webAllowedIpCidr : fail('webAllowedIpCidr must be set when webExternalIngress is true.'))
  : ''

// A real minReplicas=1 is eligible for idle billing; a cron desiredReplicas floor is not.
// Keep HTTP scaling enabled so overnight ledger requests can wake both apps.
var appScale = {
  minReplicas: initialMinReplicas
  maxReplicas: 1
  rules: [
    {
      name: 'http'
      http: {
        metadata: {
          concurrentRequests: '10'
        }
      }
    }
  ]
}

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
        external: webExternalIngress
        targetPort: 3000
        transport: 'http'
        allowInsecure: !webExternalIngress
        ipSecurityRestrictions: webExternalIngress ? [
          {
            name: 'operator'
            action: 'Allow'
            ipAddressRange: validatedWebAllowedIpCidr
            description: 'pilot operator only'
          }
        ] : []
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
          probes: [
            {
              type: 'Startup'
              initialDelaySeconds: 1
              periodSeconds: 5
              failureThreshold: 10
              timeoutSeconds: 5
              tcpSocket: {
                port: 3000
              }
            }
            {
              type: 'Readiness'
              initialDelaySeconds: 5
              periodSeconds: 5
              failureThreshold: 3
              timeoutSeconds: 5
              tcpSocket: {
                port: 3000
              }
            }
            {
              type: 'Liveness'
              initialDelaySeconds: 15
              periodSeconds: 10
              failureThreshold: 3
              timeoutSeconds: 5
              tcpSocket: {
                port: 3000
              }
            }
          ]
        }
      ]
      scale: appScale
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
      secrets: concat([
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
          name: 'qr-token-enc-keys'
          keyVaultUrl: qrTokenEncryptionKeysKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
      ], onboardingInviteCodeKeyVaultUrl == '' ? [] : [
        {
          name: 'onboard-invite-code'
          keyVaultUrl: onboardingInviteCodeKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
      ], naverApiHubClientIdKeyVaultUrl == '' || naverApiHubClientSecretKeyVaultUrl == '' ? [] : [
        {
          name: 'naver-hub-id'
          keyVaultUrl: naverApiHubClientIdKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
        {
          name: 'naver-hub-secret'
          keyVaultUrl: naverApiHubClientSecretKeyVaultUrl
          identity: apiRuntimeIdentityResourceId
        }
      ])
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
          probes: [
            {
              type: 'Startup'
              initialDelaySeconds: 1
              periodSeconds: 10
              failureThreshold: 10
              timeoutSeconds: 5
              tcpSocket: {
                port: 8080
              }
            }
            {
              type: 'Readiness'
              initialDelaySeconds: 10
              periodSeconds: 10
              failureThreshold: 3
              timeoutSeconds: 5
              tcpSocket: {
                port: 8080
              }
            }
            {
              type: 'Liveness'
              initialDelaySeconds: 30
              periodSeconds: 15
              failureThreshold: 3
              timeoutSeconds: 5
              tcpSocket: {
                port: 8080
              }
            }
          ]
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
              secretRef: 'qr-token-enc-keys'
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
          ], onboardingInviteCodeKeyVaultUrl == '' ? [] : [
            {
              name: 'TIEAT_ONBOARDING_INVITE_CODE'
              secretRef: 'onboard-invite-code'
            }
          ], naverApiHubClientIdKeyVaultUrl == '' || naverApiHubClientSecretKeyVaultUrl == '' ? [] : [
            {
              name: 'NAVER_API_HUB_CLIENT_ID'
              secretRef: 'naver-hub-id'
            }
            {
              name: 'NAVER_API_HUB_CLIENT_SECRET'
              secretRef: 'naver-hub-secret'
            }
          ], apiTrustedProxyCidrs == '' ? [] : [
            {
              name: 'TIEAT_SECURITY_PUBLIC_QR_CREATE_TRUSTED_PROXY_CIDRS'
              value: apiTrustedProxyCidrs
            }
          ])
        }
      ]
      scale: appScale
      // Explicit TCP probes avoid probing the protected actuator endpoints.
    }
  }
}
