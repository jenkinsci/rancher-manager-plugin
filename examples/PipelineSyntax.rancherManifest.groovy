// Pipeline example for Rancher Manifest Deployment (symbol: rancherManifest)
//
// Prerequisites:
//   1. Manage Jenkins → Credentials → Secret text with Rancher Bearer Token
//   2. Manage Jenkins → System → Rancher Manager (Inherit):
//        Rancher URL: https://rancher.example
//        API token credentials: the Secret text above
//   3. clusterId from Rancher UI (e.g. local, c-m-…)
//
// YAML apply uses Steve action=apply, then waits for workloads in the YAML (see docs/API.md).
// Namespace comes from the manifest (metadata.namespace and/or kind: Namespace).
// Hosts: rancher.example / gitlab.example only
//
// Manual YAML: agent none. Repository source needs an agent (Git fetch).

pipeline {
    agent none
    stages {
        stage('Preflight cluster') {
            steps {
                rancherManifest(clusterId: 'local')
            }
        }
        stage('Apply manifest (Manual YAML)') {
            steps {
                rancherManifest(
                    clusterId: 'local',
                    manifestSource: 'yaml',
                    manifestYaml: '''
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-config
data:
  key: value
''',
                    waitTimeoutSeconds: '300'  // Settle timeout: Jenkins poll after apply (default 300)
                )
            }
        }
        stage('Apply manifest (Repository)') {
            agent any
            steps {
                rancherManifest(
                    clusterId: 'local',
                    repositoryUrl: 'https://gitlab.example/group/manifests.git',
                    manifestFilePath: 'manifest.yaml',
                    repositoryReferenceName: 'refs/heads/main',
                    gitCredentialsId: 'git-clone'
                )
            }
        }
        stage('Validate only') {
            steps {
                rancherManifest(
                    clusterId: 'local',
                    manifestSource: 'yaml',
                    manifestYaml: '''
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-config
''',
                    validateOnly: true
                )
            }
        }
    }
}
