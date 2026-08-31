targetScope = 'subscription'

@description('Name of the existing resource group containing the private receipt storage account.')
param storageAccountResourceGroupName string

var roleDefinitionName = guid(subscription().id, storageAccountResourceGroupName, 'tieat-receipt-blob-tags-reader')

resource receiptBlobTagsReaderRoleDefinition 'Microsoft.Authorization/roleDefinitions@2022-04-01' = {
  name: roleDefinitionName
  properties: {
    roleName: 'TIEAT Receipt Blob Tags Reader ${uniqueString(subscription().id, storageAccountResourceGroupName)}'
    description: 'Read blob index tags used to gate private receipt downloads after malware scanning.'
    type: 'customRole'
    permissions: [
      {
        actions: []
        notActions: []
        dataActions: [
          'Microsoft.Storage/storageAccounts/blobServices/containers/blobs/tags/read'
        ]
        notDataActions: []
      }
    ]
    assignableScopes: [
      resourceId('Microsoft.Resources/resourceGroups', storageAccountResourceGroupName)
    ]
  }
}

output roleDefinitionResourceId string = receiptBlobTagsReaderRoleDefinition.id
