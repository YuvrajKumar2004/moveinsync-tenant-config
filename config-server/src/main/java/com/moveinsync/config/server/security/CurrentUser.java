package com.moveinsync.config.server.security;

import com.moveinsync.config.server.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Access to the authenticated caller from controllers. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return TokenService.toUser(jwt.getToken());
        }
        throw ApiException.unauthenticated();
    }
}
