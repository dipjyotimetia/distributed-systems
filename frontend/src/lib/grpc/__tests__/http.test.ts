/**
 * @jest-environment node
 */
import { Code, ConnectError } from '@connectrpc/connect';
import { grpcResponse } from '../http';

describe('grpcResponse', () => {
  it('returns the protobuf JSON body', async () => {
    const response = await grpcResponse(
      Promise.resolve({ status: 'PENDING', approved: false }),
    );
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      status: 'PENDING',
      approved: false,
    });
  });

  it.each([
    [Code.Unauthenticated, 401, 'missing token'],
    [Code.PermissionDenied, 403, 'not an operator'],
    [Code.InvalidArgument, 400, 'account id required'],
    [Code.Unavailable, 502, 'fraud screening unavailable'],
  ])('maps %s to HTTP %s', async (code, status, message) => {
    const response = await grpcResponse(
      Promise.reject(new ConnectError(message, code)),
    );
    expect(response.status).toBe(status);
    await expect(response.json()).resolves.toEqual({ message });
  });
});
