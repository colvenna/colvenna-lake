{{- define "colvenna-bi.labels" -}}
app.kubernetes.io/name: colvenna-bi
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: colvenna-lake
{{- end }}

{{- define "colvenna-bi.selector" -}}
app.kubernetes.io/name: colvenna-bi
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* The environment Superset, colvenna_bi and the setup job share. */}}
{{- define "colvenna-bi.env" -}}
- name: AWS_REGION
  value: {{ required "region" .Values.region | quote }}
- name: SUPERSET_SECRET_KEY
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.app }}, key: secret-key}}
- name: COLVENNA_BI_GUEST_TOKEN_SECRET
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.app }}, key: guest-token-secret}}
- name: COLVENNA_BI_METADATA_URI
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.metadata }}, key: uri}}
- name: COLVENNA_PUBLIC_URL
  value: {{ required "entry.publicUrl" .Values.entry.publicUrl | quote }}
- name: COLVENNA_SIGN_IN_URL
  value: {{ required "entry.signInUrl" .Values.entry.signInUrl | quote }}
- name: COLVENNA_USER_POOL_ID
  value: {{ required "entry.userPoolId" .Values.entry.userPoolId | quote }}
- name: COLVENNA_CLIENT_ID
  value: {{ required "entry.clientId" .Values.entry.clientId | quote }}
- name: COLVENNA_LOAD_BALANCER_ARN
  value: {{ required "entry.loadBalancerArn" .Values.entry.loadBalancerArn | quote }}
- name: COLVENNA_SESSION_COOKIE
  value: {{ required "entry.sessionCookie" .Values.entry.sessionCookie | quote }}
{{- if not .Values.entry.roleGroups }}{{ fail "entry.roleGroups is required" }}{{ end }}
- name: COLVENNA_ROLE_GROUPS
  value: {{ .Values.entry.roleGroups | toJson | quote }}
- name: COLVENNA_DORIS_HOST
  value: {{ required "doris.host" .Values.doris.host | quote }}
- name: COLVENNA_DORIS_PORT
  value: {{ .Values.doris.port | quote }}
- name: COLVENNA_DORIS_USERS_DIR
  value: /etc/colvenna-bi/doris-users
- name: PYTHONDONTWRITEBYTECODE
  value: "1"
{{- end }}

{{- define "colvenna-bi.securityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end }}
