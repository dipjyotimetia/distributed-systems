using System.Text;
using System.Text.Json;

namespace UserServiceDotnet.Services;

public class IdentityVerificationService
{
    private readonly HttpClient _httpClient;
    private readonly ILogger<IdentityVerificationService> _logger;
    private readonly string _socureApiKey;
    private readonly string _jumioApiToken;
    private readonly string _hibpApiKey;
    private readonly string _socureBaseUrl;
    private readonly string _jumioBaseUrl;
    private readonly string _hibpBaseUrl;

    public IdentityVerificationService(
        HttpClient httpClient,
        ILogger<IdentityVerificationService> logger,
        IConfiguration configuration)
    {
        _httpClient = httpClient;
        _logger = logger;
        _socureApiKey = configuration["SOCURE_API_KEY"] ?? "mock-socure-key";
        _jumioApiToken = configuration["JUMIO_API_TOKEN"] ?? "mock-jumio-token";
        _hibpApiKey = configuration["HIBP_API_KEY"] ?? "mock-hibp-key";
        _socureBaseUrl = (configuration["SOCURE_BASE_URL"] ?? "https://sandbox.socure.com").TrimEnd('/');
        _jumioBaseUrl = (configuration["JUMIO_BASE_URL"] ?? "https://netverify.com").TrimEnd('/');
        _hibpBaseUrl = (configuration["HIBP_BASE_URL"] ?? "https://haveibeenpwned.com").TrimEnd('/');
    }

    public void VerifyIdentityAsync(string email, long userId)
    {
        _logger.LogInformation("Starting identity verification for user {UserId} ({Email})", userId, email);

        _ = Task.Run(async () =>
        {
            var socure = CallSocure(email);
            var jumio = CallJumio(email, userId);
            var hibp = CallHibp(email);

            try
            {
                await Task.WhenAll(socure, jumio, hibp);
            }
            catch
            {
                // individual tasks already log their own failures
            }

            _logger.LogInformation("Identity verification complete for user {UserId}", userId);
        });
    }

    private async Task CallSocure(string email)
    {
        try
        {
            _logger.LogInformation("Calling Socure ID+ for email: {Email}", email);

            using var request = new HttpRequestMessage(HttpMethod.Post, $"{_socureBaseUrl}/api/3.0/EmailAuthScore");
            request.Headers.Add("Authorization", $"SocureApiKey {_socureApiKey}");
            request.Content = new StringContent(
                JsonSerializer.Serialize(new { modules = new[] { "emailrisk" }, email }),
                Encoding.UTF8, "application/json");

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await _httpClient.SendAsync(request, cts.Token);
            _logger.LogInformation("Socure ID+ call completed for email: {Email}", email);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Socure check failed: {Message}", ex.Message);
        }
    }

    private async Task CallJumio(string email, long userId)
    {
        try
        {
            _logger.LogInformation("Calling Jumio for user: {UserId}", userId);

            using var request = new HttpRequestMessage(HttpMethod.Post, $"{_jumioBaseUrl}/api/v4/initiate");
            request.Headers.Add("Authorization", $"Bearer {_jumioApiToken}");
            request.Content = new StringContent(
                JsonSerializer.Serialize(new { customerInternalReference = $"apex-user-{userId}", userReference = email }),
                Encoding.UTF8, "application/json");

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await _httpClient.SendAsync(request, cts.Token);
            _logger.LogInformation("Jumio call completed for user: {UserId}", userId);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Jumio check failed: {Message}", ex.Message);
        }
    }

    private async Task CallHibp(string email)
    {
        try
        {
            _logger.LogInformation("Calling HIBP for email: {Email}", email);

            using var request = new HttpRequestMessage(HttpMethod.Get,
                $"{_hibpBaseUrl}/api/v3/breachedaccount/{email}");
            request.Headers.Add("hibp-api-key", _hibpApiKey);
            request.Headers.Add("user-agent", "ApexBanking/1.0");

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await _httpClient.SendAsync(request, cts.Token);
            _logger.LogInformation("HIBP call completed for email: {Email}", email);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("HIBP check failed: {Message}", ex.Message);
        }
    }
}
