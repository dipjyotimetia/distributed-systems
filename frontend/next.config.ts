import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  serverExternalPackages: [
    '@bufbuild/protobuf',
    '@connectrpc/connect',
    '@connectrpc/connect-node',
  ],
  images: {
    unoptimized: true,
  },
};

export default nextConfig;
