package com.unbidden.jvtaskmanagementsystem.service.oauth2;

import com.unbidden.jvtaskmanagementsystem.dto.internal.OAuth2TokenResponseDto;
import com.unbidden.jvtaskmanagementsystem.dto.oauth2.OAuth2SuccessResponse;
import com.unbidden.jvtaskmanagementsystem.exception.EntityNotFoundException;
import com.unbidden.jvtaskmanagementsystem.exception.OAuth2AuthorizationException;
import com.unbidden.jvtaskmanagementsystem.exception.OAuth2AuthorizedClientLoadingException;
import com.unbidden.jvtaskmanagementsystem.model.AuthorizationMeta;
import com.unbidden.jvtaskmanagementsystem.model.ClientRegistration;
import com.unbidden.jvtaskmanagementsystem.model.OAuth2AuthorizedClient;
import com.unbidden.jvtaskmanagementsystem.model.User;
import com.unbidden.jvtaskmanagementsystem.repository.oauth2.AuthorizationMetaRepository;
import com.unbidden.jvtaskmanagementsystem.repository.oauth2.AuthorizedClientRepository;
import com.unbidden.jvtaskmanagementsystem.service.util.HttpClientUtil.HeaderNames;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuth2ServiceImpl implements OAuth2Service {
    private static final String AUTHORIZATION_URI_PATTERN = 
            "%s?client_id=%s&redirect_uri=%s&state=%s"
            + "&token_access_type=%s&response_type=code";

    private final OAuth2AuthorizedClientResolverManager authorizedClientResolverManager;
    
    private final AuthorizationMetaRepository authorizationMetaRepository;

    private final OAuth2Client oauthClient;

    private final AuthorizedClientRepository authorizedClientRepository;

    @Override
    public void authorize(@NonNull User user, @NonNull HttpServletResponse response,
            @NonNull ClientRegistration clientRegistration) {
        try {
            loadAuthorizedClient(user, clientRegistration);
            throw new OAuth2AuthorizationException("No need to continue authorization"
                    + " because user " + user.getId() + " is already authorized by "
                    + clientRegistration.getClientName() + ".");
        } catch (OAuth2AuthorizedClientLoadingException e) {
            //TODO: Add logging
        }
        final AuthorizationMeta meta = getAuthorizationMeta(user, clientRegistration);
        
        final String authUri = AUTHORIZATION_URI_PATTERN.formatted(
                clientRegistration.getAuthorizationUri(),
                clientRegistration.getClientId(),
                clientRegistration.getRedirectUri(),
                meta.getId().toString(),
                (clientRegistration.getUseRefreshTokens()) ? "offline" : "online");
        try {
            response.setStatus(302);
            response.addHeader(HeaderNames.LOCATION, authUri);
            response.flushBuffer();
        } catch (IOException e) {
            throw new OAuth2AuthorizationException("IOException occured during "
                    + "redirect commit.", e);
        }
    }
    
    @Override
    public OAuth2SuccessResponse callback(@NonNull HttpServletResponse response,
            @NonNull String code, @NonNull String state, String error, String errorDescription) {
        AuthorizationMeta meta = authorizationMetaRepository.load(state)
                .orElseThrow(() -> new EntityNotFoundException("There is no authorization meta "
                + "associated with provided state: <" + state + ">. This may indicate that the "
                + "authorization attempt took too long."));

        if (error != null && !error.isBlank()) {
            throw new OAuth2AuthorizationException("Error has occured during " 
                    + meta.getClientRegistration().getClientName() 
                    + " authorization. Error: " + error + "; description: " + errorDescription);
        }

        OAuth2TokenResponseDto tokenData = oauthClient.exchange(code,
                meta.getClientRegistration());
        Optional<OAuth2AuthorizedClient> authorizedClientOpt = authorizedClientRepository
                .findByUserIdAndRegistrationName(meta.getUser().getId(),
                meta.getClientRegistration().getClientName());
        authorizedClientResolverManager.getResolver(meta.getClientRegistration())
                .resolveAuthorizedClient(meta.getUser(), tokenData, authorizedClientOpt);

        return new OAuth2SuccessResponse("OAuth2 Authorization Flow has been "
                + "concluded. Service " + meta.getClientRegistration().getClientName()
                + " has been connected successfuly for user " + meta.getUser().getId() + ".");
    }

    @Override
    public OAuth2AuthorizedClient loadAuthorizedClient(User user,
            ClientRegistration clientRegistration) throws OAuth2AuthorizedClientLoadingException {
        Optional<OAuth2AuthorizedClient> authorizedClientOpt = authorizedClientRepository
                .findByUserIdAndRegistrationName(user.getId(),clientRegistration.getClientName());
        if (authorizedClientOpt.isPresent()) {
            OAuth2AuthorizedClient authorizedClient = authorizedClientOpt.get();

            if (authorizedClient.getAquiredAt()
                    .plusSeconds(authorizedClient.getExpiresIn())
                    .isAfter(LocalDateTime.now())) {
                return authorizedClient;
            }
            if (clientRegistration.getUseRefreshTokens()) {
                return authorizedClientResolverManager.getResolver(clientRegistration)
                    .resolveAuthorizedClient(user, oauthClient.refresh(authorizedClient,
                    clientRegistration), authorizedClientOpt);
            }     
        }
        throw new OAuth2AuthorizedClientLoadingException("Unable to load authorized client. "
                + "It is either expired and refresh tokens are not available "
                + "or user has never been logged in.");
    }

    @Override
    public void deleteAuthorizedClient(OAuth2AuthorizedClient authorizedClient) {
        authorizedClientRepository.delete(authorizedClient);
    }

    private AuthorizationMeta getAuthorizationMeta(User user,
            ClientRegistration clientRegistration) {
        final UUID uuid = UUID.randomUUID();

        return authorizationMetaRepository.save(new AuthorizationMeta(uuid, user,
                clientRegistration, LocalDateTime.now()));
    }
}
