package com.templeregistry.dto.response.auth;

import lombok.Builder;
import lombok.Getter;

/**
 * Bootstrap payload for the SPA's CSRF handling (H-5).
 *
 * <p>The token is also delivered as the readable {@code XSRF-TOKEN} cookie. It is returned in
 * the body as well so the SPA works when it is served from a different origin than the API,
 * where {@code document.cookie} cannot see a cookie belonging to the API domain.</p>
 *
 * <p>This exposes no authentication secret: the CSRF token is a per-browser random value that
 * proves the request came from a page able to read the response, not an identity claim.</p>
 */
@Getter
@Builder
public class CsrfTokenResponse {
    private String token;           // the value to echo back
    private String headerName;      // X-XSRF-TOKEN
    private String parameterName;   // _csrf, for form posts
}
