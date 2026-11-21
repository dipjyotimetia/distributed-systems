import fs from 'fs';
import { createClient, type CallOptions } from '@connectrpc/connect';
import { createGrpcTransport } from '@connectrpc/connect-node';
import {
  fromJson,
  toJson,
  type DescMessage,
  type JsonValue,
  type MessageShape,
} from '@bufbuild/protobuf';
import {
  ApplyRequestSchema,
  CaseSchema,
  DecideRequestSchema,
  GetStatusRequestSchema,
  Kyc,
  QueueRequestSchema,
  QueueResponseSchema,
  StatusSchema,
} from '@/gen/kyc/kyc_pb';
import {
  AuthorizationSchema,
  AuthorizeRequestSchema,
  CardSchema,
  Cards,
  IssueCardRequestSchema,
  ListCardsRequestSchema,
  ListCardsResponseSchema,
} from '@/gen/cards/cards_pb';

const jsonWrite = { alwaysEmitImplicit: true };

function endpoint(address: string, tls: boolean) {
  if (address.includes('://')) return address;
  return `${tls ? 'https' : 'http'}://${address}`;
}

function transport(address: string, serverName: string) {
  const tls = process.env.MTLS_ENABLED === 'true';
  return createGrpcTransport({
    baseUrl: endpoint(address, tls),
    nodeOptions: tls
      ? {
          ca: fs.readFileSync(process.env.MTLS_CA || ''),
          key: fs.readFileSync(process.env.MTLS_KEY || ''),
          cert: fs.readFileSync(process.env.MTLS_CERT || ''),
          servername: serverName,
        }
      : undefined,
  });
}

function callOptions(authorization: string | null): CallOptions {
  return authorization ? { headers: { authorization } } : {};
}

function responseJson(schema: DescMessage, message: MessageShape<DescMessage>) {
  return toJson(schema, message, jsonWrite) as Record<string, unknown>;
}

export async function call(
  target: 'kyc' | 'cards',
  method: string,
  authorization: string | null,
  request: Record<string, unknown>,
) {
  const options = callOptions(authorization);
  const body = request as JsonValue;
  if (target === 'kyc') {
    const client = createClient(
      Kyc,
      transport(process.env.KYC_GRPC_ADDR || 'kyc-service:8091', 'kyc-service'),
    );
    switch (method) {
      case 'Apply':
        return responseJson(
          CaseSchema,
          await client.apply(fromJson(ApplyRequestSchema, body), options),
        );
      case 'GetStatus':
        return responseJson(
          StatusSchema,
          await client.getStatus(
            fromJson(GetStatusRequestSchema, body),
            options,
          ),
        );
      case 'Queue':
        return responseJson(
          QueueResponseSchema,
          await client.queue(fromJson(QueueRequestSchema, body), options),
        );
      case 'Decide':
        return responseJson(
          CaseSchema,
          await client.decide(fromJson(DecideRequestSchema, body), options),
        );
      default:
        throw new Error(`unknown kyc method ${method}`);
    }
  }

  const client = createClient(
    Cards,
    transport(
      process.env.CARDS_GRPC_ADDR || 'cards-service:8092',
      'cards-service',
    ),
  );
  switch (method) {
    case 'IssueCard':
      return responseJson(
        CardSchema,
        await client.issueCard(fromJson(IssueCardRequestSchema, body), options),
      );
    case 'ListCards':
      return responseJson(
        ListCardsResponseSchema,
        await client.listCards(fromJson(ListCardsRequestSchema, body), options),
      );
    case 'Authorize':
      return responseJson(
        AuthorizationSchema,
        await client.authorize(fromJson(AuthorizeRequestSchema, body), options),
      );
    default:
      throw new Error(`unknown cards method ${method}`);
  }
}
