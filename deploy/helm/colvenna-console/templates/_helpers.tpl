{{- define "colvenna-console.labels" -}}
app.kubernetes.io/name: colvenna-console
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: colvenna-lake
{{- end }}

{{- define "colvenna-console.selector" -}}
app.kubernetes.io/name: colvenna-console
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
