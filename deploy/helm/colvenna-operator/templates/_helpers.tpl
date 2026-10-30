{{- define "colvenna-operator.labels" -}}
app.kubernetes.io/name: colvenna-operator
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: colvenna-lake
{{- end }}

{{- define "colvenna-operator.selector" -}}
app.kubernetes.io/name: colvenna-operator
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
