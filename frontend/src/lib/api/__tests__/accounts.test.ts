import { AccountsAPI } from '../accounts'

jest.mock('@/gen/openapi/accounts/src', () => {
  const methods = {
    listAccounts: jest.fn(),
    getAccount: jest.fn(),
    createAccount: jest.fn(),
    updateAccount: jest.fn(),
    closeAccount: jest.fn(),
    summarizeAccounts: jest.fn(),
    getAccountBalance: jest.fn(),
    updateAccountStatus: jest.fn(),
    searchAccounts: jest.fn(),
  }
  return { Configuration: jest.fn(), AccountsApi: jest.fn(() => methods), methods }
})

const { methods } = jest.requireMock('@/gen/openapi/accounts/src') as {
  methods: Record<string, jest.Mock>
}

describe('AccountsAPI', () => {
  beforeEach(() => jest.clearAllMocks())

  it('lists accounts through the generated client', async () => {
    const data = [{ id: 1, accountNumber: '1234567890', accountType: 'CHECKING' as const, balance: 1000 }]
    methods.listAccounts.mockResolvedValue(data)
    await expect(AccountsAPI.getAccounts()).resolves.toEqual({ success: true, data })
    expect(methods.listAccounts).toHaveBeenCalledWith()
  })

  it('passes pagination to the generated client', async () => {
    methods.listAccounts.mockResolvedValue({ content: [] })
    await AccountsAPI.getAccountsPaginated()
    expect(methods.listAccounts).toHaveBeenCalledWith({ page: 0, size: 10, sort: 'createdAt,desc' })
    await AccountsAPI.getAccountsPaginated(2, 5, 'balance,asc')
    expect(methods.listAccounts).toHaveBeenCalledWith({ page: 2, size: 5, sort: 'balance,asc' })
  })

  it('reads, creates, updates, and closes an account', async () => {
    methods.getAccount.mockResolvedValue({ id: 1 })
    await expect(AccountsAPI.getAccount(1)).resolves.toEqual({ success: true, data: { id: 1 } })
    expect(methods.getAccount).toHaveBeenCalledWith({ accountId: 1 })

    const accountData = { accountType: 'CHECKING' as const, initialBalance: 1000, currency: 'USD' }
    methods.createAccount.mockResolvedValue({ id: 1, ...accountData })
    await AccountsAPI.createAccount(accountData)
    expect(methods.createAccount).toHaveBeenCalledWith({ accountCreateRequest: accountData })

    methods.updateAccount.mockResolvedValue({ id: 1, accountType: 'SAVINGS' })
    await AccountsAPI.updateAccount(1, { accountType: 'SAVINGS' })
    expect(methods.updateAccount).toHaveBeenCalledWith({ accountId: 1, accountUpdateRequest: { accountType: 'SAVINGS' } })

    methods.closeAccount.mockResolvedValue(undefined)
    await expect(AccountsAPI.deleteAccount(1)).resolves.toEqual({ success: true, data: undefined })
    expect(methods.closeAccount).toHaveBeenCalledWith({ accountId: 1 })
  })

  it('returns the client error message when creation fails', async () => {
    methods.createAccount.mockRejectedValue(new Error('Validation error'))
    await expect(AccountsAPI.createAccount({ accountType: 'CHECKING', initialBalance: -100 })).resolves.toEqual({
      success: false,
      message: 'Validation error',
    })
  })

  it('reads summary and balance and changes status', async () => {
    methods.summarizeAccounts.mockResolvedValue({ totalAccounts: 2 })
    await AccountsAPI.getAccountSummary()
    expect(methods.summarizeAccounts).toHaveBeenCalled()

    methods.getAccountBalance.mockResolvedValue({ balance: 1000, currency: 'USD' })
    await AccountsAPI.getAccountBalance(1)
    expect(methods.getAccountBalance).toHaveBeenCalledWith({ accountId: 1 })

    methods.updateAccountStatus.mockResolvedValue({ id: 1, status: 'FROZEN' })
    await AccountsAPI.freezeAccount(1)
    await AccountsAPI.unfreezeAccount(1)
    await AccountsAPI.closeAccount(1)
    expect(methods.updateAccountStatus).toHaveBeenNthCalledWith(1, { accountId: 1, statusUpdateRequest: { status: 'FROZEN' } })
    expect(methods.updateAccountStatus).toHaveBeenNthCalledWith(2, { accountId: 1, statusUpdateRequest: { status: 'ACTIVE' } })
    expect(methods.updateAccountStatus).toHaveBeenNthCalledWith(3, { accountId: 1, statusUpdateRequest: { status: 'CLOSED' } })
  })

  it('filters and searches through the generated client', async () => {
    methods.listAccounts.mockResolvedValue([])
    await AccountsAPI.getAccountsByType('CHECKING')
    await AccountsAPI.getAccountsByStatus('ACTIVE')
    expect(methods.listAccounts).toHaveBeenCalledWith({ type: 'CHECKING' })
    expect(methods.listAccounts).toHaveBeenCalledWith({ status: 'ACTIVE' })

    methods.searchAccounts.mockResolvedValue({ content: [] })
    await AccountsAPI.searchAccounts('checking')
    await AccountsAPI.searchAccounts('savings', 1, 5)
    expect(methods.searchAccounts).toHaveBeenCalledWith({ q: 'checking', page: 0, size: 10 })
    expect(methods.searchAccounts).toHaveBeenCalledWith({ q: 'savings', page: 1, size: 5 })
  })
})
