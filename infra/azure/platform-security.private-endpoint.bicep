targetScope = 'resourceGroup'

@description('Name of the new private endpoint.')
param endpointName string

@description('Azure region for the private endpoint.')
param location string

@description('Resource ID of the existing subnet reserved for private endpoints.')
param subnetResourceId string

@description('Full resource ID of the existing service receiving the private endpoint connection.')
param privateLinkServiceResourceId string

@description('Private Link group ID exposed by the target service, such as vault, blob, or postgresqlServer.')
param groupId string

@description('Resource ID of the existing Private DNS zone associated with the target service.')
param privateDnsZoneResourceId string

resource privateEndpoint 'Microsoft.Network/privateEndpoints@2025-07-01' = {
  name: endpointName
  location: location
  properties: {
    subnet: {
      id: subnetResourceId
    }
    privateLinkServiceConnections: [
      {
        name: groupId
        properties: {
          privateLinkServiceId: privateLinkServiceResourceId
          groupIds: [
            groupId
          ]
        }
      }
    ]
  }
}

resource privateDnsZoneGroup 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2025-07-01' = {
  parent: privateEndpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: groupId
        properties: {
          privateDnsZoneId: privateDnsZoneResourceId
        }
      }
    ]
  }
}
