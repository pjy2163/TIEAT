targetScope = 'resourceGroup'

param managedEnvironmentId string
param webName string
param apiName string
@description('Immutable Web image containing /app/set-min-replicas.mjs; no separate CLI image is needed.')
param webImage string
param acrServer string
@description('Existing AcrPull-only identity. The new scheduling identity receives no registry, DB, or vault roles.')
param acrPullIdentityResourceId string

@minValue(0)
@maxValue(23)
param warmupHourKst int = 7
@minValue(0)
@maxValue(59)
param warmupMinuteKst int = 55
@minValue(0)
@maxValue(23)
param sleepHourKst int = 0
@minValue(0)
@maxValue(59)
param sleepMinuteKst int = 0

resource targetApps 'Microsoft.App/containerApps@2025-01-01' existing = [for name in [webName, apiName]: {
  name: name
}]

resource schedulerIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${webName}-scale-scheduler'
  location: resourceGroup().location
}

// ARM has no minReplicas-only permission. Restrict read/write to these two app resources.
resource schedulerRole 'Microsoft.Authorization/roleDefinitions@2022-04-01' = {
  name: guid(resourceGroup().id, webName, apiName, 'scale-scheduler-role')
  properties: {
    roleName: '${webName}-scale-scheduler'
    description: 'Read/update the two TIEAT Container Apps; no secret-list, DB, storage, or role-assignment actions.'
    type: 'CustomRole'
    assignableScopes: [resourceGroup().id]
    permissions: [{
      actions: ['Microsoft.App/containerApps/read', 'Microsoft.App/containerApps/write']
      notActions: []
      dataActions: []
      notDataActions: []
    }]
  }
}

resource schedulerAssignments 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (name, i) in [webName, apiName]: {
  name: guid(targetApps[i].id, schedulerIdentity.id, schedulerRole.id)
  scope: targetApps[i]
  properties: {
    principalId: schedulerIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: schedulerRole.id
  }
}]

// Container Apps Job cron uses UTC. KST has no daylight-saving adjustment.
var schedules = [
  { name: 'warm', cron: '${warmupMinuteKst} ${(warmupHourKst + 15) % 24} * * *', min: '1' }
  { name: 'sleep', cron: '${sleepMinuteKst} ${(sleepHourKst + 15) % 24} * * *', min: '0' }
]

resource jobs 'Microsoft.App/jobs@2025-01-01' = [for schedule in schedules: {
  name: '${webName}-scale-${schedule.name}'
  location: resourceGroup().location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${acrPullIdentityResourceId}': {}
      '${schedulerIdentity.id}': {}
    }
  }
  properties: {
    environmentId: managedEnvironmentId
    configuration: {
      triggerType: 'Schedule'
      replicaTimeout: 300
      replicaRetryLimit: 1
      scheduleTriggerConfig: {
        cronExpression: schedule.cron
        parallelism: 1
        replicaCompletionCount: 1
      }
      registries: [{ server: acrServer, identity: acrPullIdentityResourceId }]
    }
    template: {
      containers: [{
        name: 'scale-scheduler'
        image: webImage
        args: ['/app/set-min-replicas.mjs']
        resources: { cpu: json('0.25'), memory: '0.5Gi' }
        env: [
          { name: 'AZURE_CLIENT_ID', value: schedulerIdentity.properties.clientId }
          { name: 'TIEAT_SCALE_TARGETS', value: string([targetApps[0].id, targetApps[1].id]) }
          { name: 'TIEAT_SCALE_ENVIRONMENT_ID', value: managedEnvironmentId }
          { name: 'TIEAT_MIN_REPLICAS', value: schedule.min }
          { name: 'TIEAT_ACTIVE_START_KST', value: string(warmupHourKst * 60 + warmupMinuteKst) }
          { name: 'TIEAT_ACTIVE_END_KST', value: string(sleepHourKst * 60 + sleepMinuteKst) }
        ]
      }]
    }
  }
  dependsOn: [schedulerAssignments]
}]

output jobNames array = [for (schedule, i) in schedules: jobs[i].name]
