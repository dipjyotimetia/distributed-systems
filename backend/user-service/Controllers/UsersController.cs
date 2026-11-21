using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using UserServiceDotnet.Generated.Controllers;
using UserServiceDotnet.Generated.Models;
using UserServiceDotnet.Services;

namespace UserServiceDotnet.Controllers;

public class UsersController : UsersApiController
{
    private readonly UserService _userService;
    private readonly ILogger<UsersController> _logger;

    public UsersController(UserService userService, ILogger<UsersController> logger)
    {
        _userService = userService;
        _logger = logger;
    }

    public override async Task<IActionResult> Register(RegisterRequest request)
    {
        try
        {
            _logger.LogInformation("Registration request for username: {Username}", request.Username);

            var user = await _userService.RegisterUser(request.Username, request.Email, request.Password);

            return StatusCode(201, ContractUser(user));
        }
        catch (InvalidOperationException ex) when (ex.Message.Contains("already exists"))
        {
            _logger.LogError("Registration failed: {Message}", ex.Message);
            return Conflict(ContractError(ex.Message));
        }
        catch (Exception ex)
        {
            _logger.LogError("Registration failed: {Message}", ex.Message);
            return BadRequest(ContractError(ex.Message));
        }
    }

    public override async Task<IActionResult> Login(LoginRequest request)
    {
        try
        {
            _logger.LogInformation("Login request for: {UsernameOrEmail}", request.UsernameOrEmail);

            var (user, token) = await _userService.Authenticate(request.UsernameOrEmail, request.Password);

            return Ok(new LoginResponse
            {
                Token = token,
                Type = "Bearer",
                Id = user.Id,
                Username = user.Username,
                Email = user.Email,
                Roles = ContractRoles(user.Roles)
            });
        }
        catch (InvalidOperationException ex) when (ex.Message.Contains("Invalid credentials"))
        {
            _logger.LogError("Authentication failed: {Message}", ex.Message);
            return Unauthorized(ContractError(ex.Message));
        }
        catch (Exception ex)
        {
            _logger.LogError("Authentication failed: {Message}", ex.Message);
            return BadRequest(ContractError(ex.Message));
        }
    }

    public override async Task<IActionResult> GetProfile()
    {
        var authHeader = Request.Headers.Authorization.ToString();
        if (string.IsNullOrEmpty(authHeader) || !authHeader.StartsWith("Bearer "))
            return Unauthorized(ContractError("Missing or invalid token"));

        var token = authHeader["Bearer ".Length..];
        var principal = _userService.ValidateToken(token);
        if (principal == null)
            return Unauthorized(ContractError("Invalid token"));

        var username = principal.FindFirst(ClaimTypes.NameIdentifier)?.Value
                    ?? principal.FindFirst("sub")?.Value;
        if (username == null)
            return Unauthorized(ContractError("Invalid token claims"));

        try
        {
            _logger.LogInformation("Profile request for user: {Username}", username);
            var user = await _userService.GetProfile(username);

            return Ok(new ProfileResponse
            {
                Success = true,
                Message = "Profile retrieved successfully",
                Data = ContractUser(user)
            });
        }
        catch (Exception ex)
        {
            _logger.LogError("Profile retrieval failed: {Message}", ex.Message);
            return BadRequest(ContractError(ex.Message));
        }
    }

    public override async Task<IActionResult> CheckUsername(string username)
    {
        _logger.LogDebug("Checking username availability: {Username}", username);
        var exists = await _userService.UsernameExists(username);

        return Ok(ContractAvailability(!exists, exists ? "Username is not available" : "Username is available"));
    }

    public override async Task<IActionResult> CheckEmail(string email)
    {
        _logger.LogDebug("Checking email availability: {Email}", email);
        var exists = await _userService.EmailExists(email);

        return Ok(ContractAvailability(!exists, exists ? "Email is not available" : "Email is available"));
    }

    private static User ContractUser(UserServiceDotnet.Models.User user) => new()
    {
        Id = user.Id,
        Username = user.Username,
        Email = user.Email,
        Roles = ContractRoles(user.Roles),
        CreatedAt = ContractTime(user.CreatedAt),
        UpdatedAt = ContractTime(user.UpdatedAt ?? user.CreatedAt)
    };

    private static List<string> ContractRoles(string roles) =>
        string.IsNullOrEmpty(roles) ? new List<string>() : new List<string> { roles };

    private static DateTime ContractTime(DateTime value) => value.Kind switch
    {
        DateTimeKind.Utc => value,
        DateTimeKind.Local => value.ToUniversalTime(),
        _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
    };

    private static ErrorResponse ContractError(string message) => new()
    {
        Success = false,
        Message = message
    };

    private static Availability ContractAvailability(bool available, string message) => new()
    {
        Success = true,
        Available = available,
        Message = message
    };
}
