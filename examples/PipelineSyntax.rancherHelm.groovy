// Pipeline example for Rancher Helm Deployment (symbol: rancherHelm)
//
// Prerequisites:
//   1. Manage Jenkins → Credentials → Secret text with Rancher Bearer Token
//   2. Manage Jenkins → System → Rancher Manager (Inherit):
//        Rancher URL: https://rancher.example
//        API token credentials: the Secret text above
//
// Helm uses Rancher catalog ?action=install|upgrade (see docs/API.md).
// Chart repo URL (https:// or oci://) must match an existing ClusterRepo.
// Project is the Rancher project name (or p-xxxxx), not clusterId:p-xxxxx.
// Hosts: rancher.example / charts.example / registry.example / gitlab.example only
//
// valuesSource none/yaml: agent none. repository needs an agent with git.

pipeline {
    agent any
    stages {
        stage('Preflight cluster') {
            steps {
                rancherHelm(
                    clusterId: 'local',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    project: 'Default',
                    validateOnly: true
                )
            }
        }
        stage('Install / upgrade (no values)') {
            steps {
                rancherHelm(
                    clusterId: 'local',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    project: 'Default',
                    namespace: 'default',
                    waitTimeoutSeconds: '300',
                    helmWait: true,
                    helmTimeoutSeconds: '300',
                    cleanupOnFail: true
                )
            }
        }
        stage('Install with Manual YAML values') {
            steps {
                rancherHelm(
                    clusterId: 'local',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    project: 'Default',
                    namespace: 'default',
                    valuesSource: 'yaml',
                    values: '''
replicaCount: 1
''',
                    helmWait: true,
                    helmTimeoutSeconds: '300',
                    atomic: true,
                    cleanupOnFail: true
                )
            }
        }
        stage('Upgrade with values overlay (set image)') {
            steps {
                rancherHelm(
                    clusterId: 'local',
                    releaseName: 'mnp',
                    chart: 'mnp',
                    repo: 'https://charts.example/helm',
                    project: 'Default',
                    version: '0.1.6',
                    namespace: 'mnp',
                    valuesSource: 'repository',
                    valuesRepositoryUrl: 'https://gitlab.example/group/helm-values.git',
                    valuesFilePath: 'mnp/values.yaml',
                    valuesOverlay: '''
backend:
  image:
    tag: "v11-dev-abc"
frontend:
  image:
    tag: "v11-dev-def"
'''
                )
            }
        }
        stage('Validate only') {
            steps {
                rancherHelm(
                    clusterId: 'local',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    project: 'Default',
                    namespace: 'default',
                    validateOnly: true
                )
            }
        }
    }
}
