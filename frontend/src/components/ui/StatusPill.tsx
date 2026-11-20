import React from 'react';

type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

const tones: Record<StatusTone, string> = {
  success: 'bg-emerald-50 text-emerald-800',
  warning: 'bg-amber-50 text-amber-900',
  danger: 'bg-red-50 text-red-800',
  neutral: 'bg-stone-100 text-stone-700',
  info: 'bg-[#f6f1e6] text-ink',
};

const known: Record<string, StatusTone> = {
  ACTIVE: 'success',
  COMPLETED: 'success',
  APPROVED: 'success',
  PENDING: 'warning',
  INACTIVE: 'neutral',
  CANCELLED: 'neutral',
  FAILED: 'danger',
  FROZEN: 'danger',
  CLOSED: 'danger',
  DECLINED: 'danger',
};

interface StatusPillProps {
  status: string;
  label?: string;
}

const StatusPill: React.FC<StatusPillProps> = ({ status, label }) => {
  const tone = known[status] ?? 'neutral';
  const text = label ?? status.charAt(0) + status.slice(1).toLowerCase();

  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium ${tones[tone]}`}>
      {text}
    </span>
  );
};

export default StatusPill;
