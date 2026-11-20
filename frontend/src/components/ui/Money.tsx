import React from 'react';
import { formatMoney } from '@/lib/format';

interface MoneyProps {
  amount: number;
  currency?: string;
  signed?: 'credit' | 'debit';
  className?: string;
}

const Money: React.FC<MoneyProps> = ({ amount, currency = 'USD', signed, className = '' }) => {
  const prefix = signed === 'credit' ? '+' : signed === 'debit' ? '−' : '';
  const tone = signed === 'credit' ? 'text-emerald-700' : signed === 'debit' ? 'text-red-700' : 'text-ink';

  return (
    <span className={`money ${tone} ${className}`}>
      {prefix}
      {formatMoney(amount, currency)}
    </span>
  );
};

export default Money;
