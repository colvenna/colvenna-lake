// Command colvenna-console is Colvenna Lake's web console. It runs behind the entry's load balancer,
// which signs users in through Cognito; the console verifies the load balancer's claims and the
// user's access token on every request, serves only users with a role, and audits every request.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs"
	cip "github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/rest"

	"github.com/colvenna/colvenna-lake/colvenna-console/internal/audit"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/identity"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/operations"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/pipeline"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/server"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("%s is not set", name)
	}
	return v
}

func run() error {
	region := env("AWS_REGION")
	pool := env("COLVENNA_USER_POOL_ID")
	pod := env("POD_NAME")
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	awsConfig, err := config.LoadDefaultConfig(ctx, config.WithRegion(region))
	if err != nil {
		return err
	}
	stream, err := audit.NewStream(ctx, cloudwatchlogs.NewFromConfig(awsConfig), env("COLVENNA_AUDIT_LOG_GROUP"),
		fmt.Sprintf("%s-%d", pod, time.Now().Unix()), os.Stdout, 1000)
	if err != nil {
		return fmt.Errorf("audit stream: %w", err)
	}
	// The stream outlives the server: it stops after the last handler, so their events are sent.
	streamCtx, stopStream := context.WithCancel(context.Background())
	streamDone := make(chan struct{})
	go func() { stream.Run(streamCtx); close(streamDone) }()
	defer func() { stopStream(); <-streamDone }()
	roleGroups, err := identity.ParseRoleGroups(env("COLVENNA_ROLE_GROUPS"))
	if err != nil {
		return err
	}

	httpClient := &http.Client{Timeout: 5 * time.Second}
	kube, err := rest.InClusterConfig()
	if err != nil {
		return fmt.Errorf("kubernetes: %w", err)
	}
	namespaces := managedNamespaces()
	reader, err := pipelineReader(kube, namespaces)
	if err != nil {
		return err
	}
	key, err := os.ReadFile(env("COLVENNA_CONFIRM_KEY_FILE"))
	if err != nil {
		return fmt.Errorf("the confirmations' key: %w", err)
	}
	if len(key) < 32 {
		return fmt.Errorf("the confirmations' key has %d bytes, not at least 32", len(key))
	}
	issuer := fmt.Sprintf("https://cognito-idp.%s.amazonaws.com/%s", region, pool)
	verifier := &identity.Verifier{
		LoadBalancerARN: env("COLVENNA_LOAD_BALANCER_ARN"),
		RoleGroups:      roleGroups,
		Issuer:          issuer,
		ClientID:        env("COLVENNA_CLIENT_ID"),
		ALB:             &identity.ALBKeys{BaseURL: fmt.Sprintf("https://public-keys.auth.elb.%s.amazonaws.com", region), Client: httpClient, Now: time.Now},
		Cognito:         &identity.CognitoKeys{URL: issuer + "/.well-known/jwks.json", Client: httpClient, Now: time.Now},
		Now:             time.Now,
	}
	console, err := server.New(server.Config{
		PublicURL:     env("COLVENNA_PUBLIC_URL"),
		SignInURL:     env("COLVENNA_SIGN_IN_URL"),
		ClientID:      verifier.ClientID,
		SessionCookie: env("COLVENNA_SESSION_COOKIE"),
		Pod:           pod,
	}, verifier, stream, signOut{client: cip.NewFromConfig(awsConfig), pool: pool}, reader, server.Operations{
		Runner:     &operations.Runner{Config: kube},
		Confirm:    &operations.Confirmations{Key: key, TTL: 5 * time.Minute, Now: time.Now},
		Namespaces: namespaces,
	})
	if err != nil {
		return err
	}

	certificate, err := selfSigned()
	if err != nil {
		return err
	}
	srv := &http.Server{
		Addr:              ":8443",
		Handler:           console.Handler(),
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{certificate}},
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       150 * time.Second,
		// The load balancer's claims, access token and session cookie together reach about 16 KiB.
		MaxHeaderBytes: 64 << 10,
	}
	served := make(chan error, 1)
	go func() { served <- srv.ListenAndServeTLS("", "") }()
	select {
	case err := <-served:
		return err
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err = srv.Shutdown(shutdown)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// signOut ends a user's sessions at the user pool.
type signOut struct {
	client *cip.Client
	pool   string
}

func (s signOut) SignOut(ctx context.Context, username string) error {
	_, err := s.client.AdminUserGlobalSignOut(ctx, &cip.AdminUserGlobalSignOutInput{
		UserPoolId: aws.String(s.pool), Username: aws.String(username),
	})
	return err
}

// selfSigned is the pod's TLS certificate, made at start. The load balancer encrypts to its targets
// without checking their certificates; only it can reach the pod.
func selfSigned() (tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		return tls.Certificate{}, err
	}
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "colvenna-console"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, nil
}

// managedNamespaces are the namespaces the console shows and acts in.
func managedNamespaces() []string {
	var namespaces []string
	for _, ns := range strings.Split(env("COLVENNA_NAMESPACES"), ",") {
		if ns = strings.TrimSpace(ns); ns != "" {
			namespaces = append(namespaces, ns)
		}
	}
	return namespaces
}

// pipelineReader reads the managed namespaces' declarations with the pod's own Kubernetes
// identity (get and list, nothing more) and the monitoring stack's Prometheus.
func pipelineReader(kube *rest.Config, namespaces []string) (*pipeline.Reader, error) {
	client, err := dynamic.NewForConfig(kube)
	if err != nil {
		return nil, err
	}
	return &pipeline.Reader{
		Kubernetes: &pipeline.Kubernetes{Client: client, Namespaces: namespaces},
		Prometheus: &pipeline.Prometheus{URL: env("COLVENNA_PROMETHEUS_URL"), Client: &http.Client{Timeout: 10 * time.Second}},
		Now:        time.Now,
	}, nil
}
