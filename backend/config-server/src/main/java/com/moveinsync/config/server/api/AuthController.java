package com.moveinsync.config.server.api;

import com.moveinsync.config.server.api.dto.Dtos;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.TokenService;
import com.moveinsync.config.server.security.UserDirectory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserDirectory users;
    private final TokenService tokens;

    public AuthController(UserDirectory users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/token")
    public Dtos.TokenResponse token(@Valid @RequestBody Dtos.TokenRequest req) {
        AuthUser user = users.authenticate(req.username(), req.password())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "invalid username or password"));
        TokenService.Issued issued = tokens.issue(user);
        return new Dtos.TokenResponse(issued.token(), "Bearer", issued.expiresInSeconds(), user.role().name(), user.tenant());
    }
}
