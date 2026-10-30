{{- define "colvenna-dev-kafka.labels" -}}
app.kubernetes.io/name: colvenna-dev-kafka
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: colvenna-lake
strimzi.io/cluster: {{ .Release.Name }}
{{- end }}

{{- define "colvenna-dev-kafka.affinity" -}}
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms:
        - matchExpressions:
            - key: {{ .Values.nodeRole.key }}
              operator: In
              values: {{ toJson .Values.nodeRole.values }}
{{- end }}

{{- define "colvenna-dev-kafka.operatorResources" -}}
resources:
  requests: {cpu: 50m, memory: 256Mi}
  limits: {cpu: 250m, memory: 256Mi}
{{- end }}
