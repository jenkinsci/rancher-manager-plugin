# Helm chart examples

Minimal `nginx:alpine` Helm chart for packaging into a chart repository used by `rancherHelm`.

| Path | Chart name | Image |
| ---- | ---------- | ----- |
| `nginx/` | `nginx` | `nginx:alpine` |

`rancherHelm` installs via chart **repository URL** (`repo`) + chart name. Schemes: `https://` / `http://` (Helm index) or `oci://` (OCI registry, Rancher **2.9+**). Package and publish the chart, then point `repo` at the same URL as the Rancher ClusterRepo `spec.url`. The ClusterRepo must already exist; the step refreshes that index only if catalog cannot find the chart, then retries once.

Rancher does not take chart-repo credentials on the Jenkins step. For private OCI/HTTP repos, configure auth on the ClusterRepo in Rancher. For public HTTP indexes, serve without auth.

Hosts: `rancher.example` / `charts.example` / `gitlab.example` / `registry.example` only. No real secrets.

## Package locally (HTTP index)

```bash
cd examples/charts
helm lint nginx
helm package nginx -d .
# Serve the directory (or upload .tgz + index.yaml) as https://charts.example/helm
helm repo index . --url https://charts.example/helm
```

## Package to OCI (local registry smoke)

```bash
cd examples/charts
helm package nginx -d .
# Example: docker run -d -p 5000:5000 --name oci-registry registry:2
helm push nginx-*.tgz oci://localhost:5000/charts
# In Rancher Apps → Repositories add OCI URL oci://<host-reachable-from-Rancher>:5000/charts
```

Use a host Rancher can reach (not `localhost` unless registry shares the Rancher node / Desktop networking).

## Example `rancherHelm` fields

```groovy
rancherHelm(
    clusterId: 'local',
    project: 'Default',
    namespace: 'default',
    releaseName: 'demo-nginx',
    chart: 'nginx',
    repo: 'https://charts.example/helm',
    // OCI example (ClusterRepo.spec.url must match):
    // repo: 'oci://registry.example/charts',
    // version: '0.1.0',
    // ensureNamespace: false,  // default true: create NS in Project if missing
    // valuesSource: 'none',    // default — omit values (chart defaults)
    valuesSource: 'yaml',
    values: '''
replicaCount: 1
service:
  type: ClusterIP
''',
    // Or fetch values from Git (Jenkins clones; Rancher catalog only gets JSON values):
    // valuesSource: 'repository',
    // valuesRepositoryUrl: 'https://gitlab.example/group/helm-values.git',
    // valuesFilePath: 'values.yaml',
    // valuesGitCredentialsId: 'git-clone',
    // valuesRepositoryReferenceName: 'refs/heads/main',
    atomic: true
)
```

Full pipeline sample: `../PipelineSyntax.rancherHelm.groovy`.

**Values source:** default **No source** (`valuesSource: 'none'` / omit). **Manual YAML** (`yaml` + `values`). **Repository** (`repository` + Git URL / path / optional creds / ref) — Jenkins shallow-clones (private repos via `GIT_ASKPASS`) and POSTs file content as Helm values JSON. Requires `git` on the agent PATH.

**Project** is required (Rancher project name or `p-xxxxx` in this cluster). **Ensure namespace:** on by default — create the namespace **in that project** if missing; set `ensureNamespace: false` to require it already exists there. Skipped under `validateOnly`. Manifest does not take Project/Namespace.
