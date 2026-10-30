# colvenna-console: a static Go binary on distroless, running as nonroot (65532). Built with
# check.sh's Go image (Go 1.27.3), the one the tests ran on.
FROM --platform=$BUILDPLATFORM golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 AS build
ARG TARGETARCH
# Built from the repository: the console uses the CLI's operations (colvenna-cli/ops, by replace).
WORKDIR /src/colvenna-console
COPY colvenna-cli/go.mod colvenna-cli/go.sum /src/colvenna-cli/
COPY colvenna-console/go.mod colvenna-console/go.sum ./
RUN go mod download && go mod verify
COPY colvenna-cli /src/colvenna-cli
COPY colvenna-console .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=$TARGETARCH go build -trimpath -ldflags "-s -w" -o /out/colvenna-console .

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/colvenna-console /colvenna-console
USER 65532:65532
EXPOSE 8443
ENTRYPOINT ["/colvenna-console"]
