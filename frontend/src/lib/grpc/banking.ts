import fs from 'fs';
import path from 'path';
import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';

type Unary = (request: Record<string, unknown>, metadata: grpc.Metadata, callback: (error: grpc.ServiceError | null, response: Record<string, unknown>) => void) => void;

function credentials() {
  if (process.env.MTLS_ENABLED !== 'true') return grpc.credentials.createInsecure();
  return grpc.credentials.createSsl(
    fs.readFileSync(process.env.MTLS_CA || ''),
    fs.readFileSync(process.env.MTLS_KEY || ''),
    fs.readFileSync(process.env.MTLS_CERT || ''),
  );
}

function service(protoFile: string, packageName: string, serviceName: string, address: string, serverName: string) {
  const definition = protoLoader.loadSync(path.join(process.cwd(), 'proto', protoFile), {
    keepCase: false,
    longs: String,
    defaults: true,
  });
  const loaded = grpc.loadPackageDefinition(definition) as Record<string, unknown>;
  const Service = packageName.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], loaded) as Record<string, grpc.ServiceClientConstructor>;
  const Client = Service[serviceName];
  return new Client(address, credentials(), {
    'grpc.ssl_target_name_override': serverName,
  });
}

const kyc = () => service('kyc/kyc.proto', 'kyc.v1', 'Kyc', process.env.KYC_GRPC_ADDR || 'kyc-service:8091', 'kyc-service');
const cards = () => service('cards/cards.proto', 'cards.v1', 'Cards', process.env.CARDS_GRPC_ADDR || 'cards-service:8092', 'cards-service');

export function call(target: 'kyc' | 'cards', method: string, authorization: string | null, request: Record<string, unknown>) {
  const client = (target === 'kyc' ? kyc() : cards()) as unknown as Record<string, Unary>;
  const metadata = new grpc.Metadata();
  if (authorization) metadata.set('authorization', authorization);
  return new Promise<Record<string, unknown>>((resolve, reject) => {
    client[method](request, metadata, (error, response) => {
      if (error) reject(error);
      else resolve(response);
    });
  });
}
