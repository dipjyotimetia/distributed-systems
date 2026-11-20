package mtls

import (
	"crypto/tls"
	"crypto/x509"
	"os"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
)

func ServerOption() (grpc.ServerOption, error) {
	if os.Getenv("MTLS_ENABLED") != "true" {
		return nil, nil
	}
	certificate, err := tls.LoadX509KeyPair(os.Getenv("MTLS_CERT"), os.Getenv("MTLS_KEY"))
	if err != nil {
		return nil, err
	}
	pool, err := poolFrom(os.Getenv("MTLS_CA"))
	if err != nil {
		return nil, err
	}
	return grpc.Creds(credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{certificate},
		ClientAuth:   tls.RequireAndVerifyClientCert,
		ClientCAs:    pool,
		MinVersion:   tls.VersionTLS12,
	})), nil
}

func DialOption(serverName string) (grpc.DialOption, error) {
	if os.Getenv("MTLS_ENABLED") != "true" {
		return grpc.WithTransportCredentials(insecure.NewCredentials()), nil
	}
	certificate, err := tls.LoadX509KeyPair(os.Getenv("MTLS_CERT"), os.Getenv("MTLS_KEY"))
	if err != nil {
		return nil, err
	}
	pool, err := poolFrom(os.Getenv("MTLS_CA"))
	if err != nil {
		return nil, err
	}
	return grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{certificate},
		RootCAs:      pool,
		ServerName:   serverName,
		MinVersion:   tls.VersionTLS12,
	})), nil
}

func poolFrom(path string) (*x509.CertPool, error) {
	pem, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(pem)
	return pool, nil
}
