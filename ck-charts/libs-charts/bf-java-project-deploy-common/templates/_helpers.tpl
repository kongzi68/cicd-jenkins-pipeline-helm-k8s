{{/*
Expand the name of the chart.
*/}}
{{- define "bf-java-project-deploy-common.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "bf-java-project-deploy-common.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else if .Values.nameOverride }}
{{- .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- else if .Values.image.imgNameOrSvcName }}
{{- .Values.image.imgNameOrSvcName | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
## 备份
# {{- $name := default .Chart.Name .Values.nameOverride -}}
# {{- printf "%s-%s" $name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- define "bf-java-project-deploy-common.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
DATA PVC名称加中缀
*/}}
{{- define "bf-java-project-deploy-common.dataPVCNameInfix" -}}
{{- if .Values.storage.dataPVCNameInfix }}
{{- printf "%s-" .Values.storage.dataPVCNameInfix -}}
{{- end }}
{{- end }}

{{/*
ALONE DATA PVC名称加中缀
*/}}
{{- define "bf-java-project-deploy-common.aloneDataPVCNameInfix" -}}
{{- if .Values.storage.aloneDataPVCNameInfix }}
{{- printf "%s-" .Values.storage.aloneDataPVCNameInfix -}}
{{- end }}
{{- end }}

{{/*
命名空间前缀组合
*/}}
{{- define "bf-java-project-deploy-common.namespacesPrefix" -}}
{{- $name := printf "%s-" .Values.namespacePrefix -}}
{{- .Release.Namespace | trimPrefix $name }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "bf-java-project-deploy-common.labels" -}}
helm.sh/chart: {{ include "bf-java-project-deploy-common.chart" . }}
{{ include "bf-java-project-deploy-common.selectorLabels" . }}
component: {{ .Values.nameOverride }}
hostNetwork: {{ .Values.hostNetwork | quote }}
internal-service: {{not .Values.hostNetwork | quote }}
managed-by: colin
created-by: colin
version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "bf-java-project-deploy-common.selectorLabels" -}}
app-name: {{ include "bf-java-project-deploy-common.name" . }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "bf-java-project-deploy-common.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "bf-java-project-deploy-common.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
