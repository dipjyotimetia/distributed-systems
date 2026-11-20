import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  serverExternalPackages: ['@grpc/grpc-js', '@grpc/proto-loader'],
  images: {
    unoptimized: true,
  },
};

export default nextConfig;
