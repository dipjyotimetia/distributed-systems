import DisputePage from '@/components/disputes/DisputePage';

export default async function Page({ searchParams }: { searchParams: Promise<{ transferId?: string }> }) {
  const id = Number((await searchParams).transferId);
  const transferId = Number.isSafeInteger(id) && id > 0 ? id : undefined;
  return <DisputePage key={transferId} transferId={transferId} />;
}
