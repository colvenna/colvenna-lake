{{- define "colvenna-monitoring.labels" -}}
app.kubernetes.io/name: colvenna-monitoring
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: colvenna-lake
{{- end }}

{{/* One component's pods: include "colvenna-monitoring.selector" (list . "prometheus") */}}
{{- define "colvenna-monitoring.selector" -}}
app.kubernetes.io/name: colvenna-monitoring
app.kubernetes.io/instance: {{ (index . 0).Release.Name }}
app.kubernetes.io/component: {{ index . 1 }}
{{- end }}
