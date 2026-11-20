package rpc

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	kycv1 "github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/mtls"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type openStatus struct{ kycv1.UnimplementedKycServer }

func (openStatus) GetStatus(context.Context, *kycv1.GetStatusRequest) (*kycv1.Status, error) {
	return &kycv1.Status{Status: "NONE"}, nil
}

func TestMutualTLSRejectsAClientWithoutACertificate(t *testing.T) {
	dir := t.TempDir()
	ca, caKey := mintCA(t)
	writePair(t, dir, "ca", ca, caKey)
	serverCertificate, serverKey := mint(t, ca, caKey, "kyc-service")
	writePair(t, dir, "server", serverCertificate, serverKey)
	clientCertificate, clientKey := mint(t, ca, caKey, "cards-service")
	writePair(t, dir, "client", clientCertificate, clientKey)
	t.Setenv("MTLS_ENABLED", "true")
	t.Setenv("MTLS_CA", filepath.Join(dir, "ca.crt"))
	t.Setenv("MTLS_CERT", filepath.Join(dir, "server.crt"))
	t.Setenv("MTLS_KEY", filepath.Join(dir, "server.key"))
	option, err := mtls.ServerOption()
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := grpc.NewServer(option)
	kycv1.RegisterKycServer(server, openStatus{})
	go server.Serve(listener)
	t.Cleanup(server.Stop)

	plain, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	defer plain.Close()
	if _, err = kycv1.NewKycClient(plain).GetStatus(context.Background(), &kycv1.GetStatusRequest{}); err == nil {
		t.Fatal("plaintext client reached a mutual TLS server")
	}

	t.Setenv("MTLS_CERT", filepath.Join(dir, "client.crt"))
	t.Setenv("MTLS_KEY", filepath.Join(dir, "client.key"))
	dial, err := mtls.DialOption("kyc-service")
	if err != nil {
		t.Fatal(err)
	}
	secure, err := grpc.NewClient(listener.Addr().String(), dial)
	if err != nil {
		t.Fatal(err)
	}
	defer secure.Close()
	response, err := kycv1.NewKycClient(secure).GetStatus(context.Background(), &kycv1.GetStatusRequest{})
	if err != nil || response.GetStatus() != "NONE" {
		t.Fatal(err, response)
	}
}

func mintCA(t *testing.T) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "banking-dev-ca"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, KeyUsage: x509.KeyUsageCertSign, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return certificate, key
}

func mint(t *testing.T, ca *x509.Certificate, caKey *ecdsa.PrivateKey, name string) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: name}, DNSNames: []string{name, "localhost"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, ca, &key.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return certificate, key
}

func writePair(t *testing.T, dir, name string, certificate *x509.Certificate, key *ecdsa.PrivateKey) tls.Certificate {
	t.Helper()
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate.Raw})
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(filepath.Join(dir, name+".crt"), certPEM, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, name+".key"), keyPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	return pair
}
