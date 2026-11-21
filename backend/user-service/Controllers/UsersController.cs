using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using UserServiceDotnet.Services;
using ApiMessage = UserServiceDotnet.Generated.Model.ApiMessage;
using Availability = UserServiceDotnet.Generated.Model.Availability;
using LoginResponse = UserServiceDotnet.Generated.Model.LoginResponse;
using ProfileResponse = UserServiceDotnet.Generated.Model.ProfileResponse;
using UserIdentity = UserServiceDotnet.Generated.Model.UserIdentity;

namespace UserServiceDotnet.Controllers;

[ApiController]
[Route("api/users")]
public class UsersController : ControllerBase
{
    private readonly UserService _userService;
    private readonly ILogger<UsersController> _logger;

    public UsersController(UserService userService, ILogger<UsersController> logger)
    {
        _userService = userService;
        _logger = logger;
    }

    public record RegisterRequest(string Username, string Email, string Password, bool? SeedAccountData);
    public record LoginRequest(string UsernameOrEmail, string Password);

    [HttpPost("register")]
    public async Task<IActionResult> Register([FromBody] RegisterRequest request)
    {
        try
        {
            _logger.LogInformation("Registration request for username: {Username}", request.Username);

            var user = await _userService.RegisterUser(request.Username, request.Email, request.Password);

            return StatusCode(201, Identity(user));
        }
        catch (InvalidOperationException ex) when (ex.Message.Contains("already exists"))
        {
            _logger.LogError("Registration failed: {Message}", ex.Message);
            return Conflict(new ApiMessage(false, ex.Message));
        }
        catch (Exception ex)
        {
            _logger.LogError("Registration failed: {Message}", ex.Message);
            return BadRequest(new ApiMessage(false, ex.Message));
        }
    }

    [HttpPost("login")]
    public async Task<IActionResult> Login([FromBody] LoginRequest request)
    {
        try
        {
            _logger.LogInformation("Login request for: {UsernameOrEmail}", request.UsernameOrEmail);

            var (user, token) = await _userService.Authenticate(request.UsernameOrEmail, request.Password);

            return Ok(new LoginResponse(token, "Bearer", user.Id, user.Username, user.Email, user.Roles));
        }
        catch (InvalidOperationException ex) when (ex.Message.Contains("Invalid credentials"))
        {
            _logger.LogError("Authentication failed: {Message}", ex.Message);
            return Unauthorized(new ApiMessage(false, ex.Message));
        }
        catch (Exception ex)
        {
            _logger.LogError("Authentication failed: {Message}", ex.Message);
            return BadRequest(new ApiMessage(false, ex.Message));
        }
    }

    [HttpGet("profile")]
    public async Task<IActionResult> GetProfile()
    {
        var authHeader = Request.Headers.Authorization.ToString();
        if (string.IsNullOrEmpty(authHeader) || !authHeader.StartsWith("Bearer "))
            return Unauthorized(new ApiMessage(false, "Missing or invalid token"));

        var token = authHeader["Bearer ".Length..];
        var principal = _userService.ValidateToken(token);
        if (principal == null)
            return Unauthorized(new ApiMessage(false, "Invalid token"));

        var username = principal.FindFirst(ClaimTypes.NameIdentifier)?.Value
                    ?? principal.FindFirst("sub")?.Value;
        if (username == null)
            return Unauthorized(new ApiMessage(false, "Invalid token claims"));

        try
        {
            _logger.LogInformation("Profile request for user: {Username}", username);
            var user = await _userService.GetProfile(username);

            return Ok(new ProfileResponse(true, "Profile retrieved successfully", Identity(user)));
        }
        catch (Exception ex)
        {
            _logger.LogError("Profile retrieval failed: {Message}", ex.Message);
            return BadRequest(new ApiMessage(false, ex.Message));
        }
    }

    [HttpGet("check-username")]
    public async Task<IActionResult> CheckUsername([FromQuery] string username)
    {
        _logger.LogDebug("Checking username availability: {Username}", username);
        var exists = await _userService.UsernameExists(username);

        return Ok(new Availability(true, !exists, exists ? "Username is not available" : "Username is available"));
    }

    [HttpGet("check-email")]
    public async Task<IActionResult> CheckEmail([FromQuery] string email)
    {
        _logger.LogDebug("Checking email availability: {Email}", email);
        var exists = await _userService.EmailExists(email);

        return Ok(new Availability(true, !exists, exists ? "Email is not available" : "Email is available"));
    }

    private static UserIdentity Identity(UserServiceDotnet.Models.User user) =>
        new(user.Id, user.Username, user.Email, user.Roles, user.CreatedAt, user.UpdatedAt);
}
