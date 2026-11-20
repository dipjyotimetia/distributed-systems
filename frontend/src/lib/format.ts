export function formatMoney(amount: number, currency = 'USD') {
  const safeCurrency = currency || 'USD';

  try {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: safeCurrency,
    }).format(amount);
  } catch {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(amount);
  }
}

export function maskAccount(accountNumber: string) {
  const compact = accountNumber.replace(/\s/g, '');
  return `****${compact.slice(-4)}`;
}

export function formatAccountType(accountType: string) {
  return accountType.charAt(0).toUpperCase() + accountType.slice(1).toLowerCase();
}
