/*
  Copyright 2012 - 2015 pac4j organization

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.pac4j.play.java;

import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.RedirectAction;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.play.CallbackController;
import org.pac4j.play.Config;
import org.pac4j.play.StorageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.libs.F.Function;
import play.libs.F.Function0;
import play.libs.F.Promise;
import play.mvc.Action;
import play.mvc.Http.Context;
import play.mvc.Result;

/**
 * <p>This action checks if the user is not authenticated and starts the authentication process if necessary.</p>
 * <p>It handles both statefull (default) or stateless resources by delegating to a pac4j client.</p>
 * <ul>
 * <li>If statefull, it relies on the session and on the callback filter to terminate the authentication process.</li>
 * <li>If stateless it validates the provided credentials and forward the request to the underlying resource if the authentication succeeds.</li>
 * </ul>
 * <p>The filter also handles basic authorization based on two parameters: requireAnyRole and requireAllRoles.</p>
 * 
 * @author Jerome Leleu
 * @author Michael Remond
 * @since 1.0.0
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class RequiresAuthenticationAction extends Action<Result> {

    private static final Logger logger = LoggerFactory.getLogger(RequiresAuthenticationAction.class);

    /**
     * Authentication algorithm.
     * 
     */
    @Override
    public Promise<Result> call(final Context ctx) {

        final ActionContext actionContext = ActionContext.build(ctx, configuration);

        // Retrieve User Profile
        Promise<CommonProfile> profile = retrieveUserProfile(actionContext);
        return profile.flatMap(new Function<CommonProfile, Promise<Result>>() {

            @Override
            public Promise<Result> apply(CommonProfile profile) throws Throwable {
                // authentication success or failure strategy
                if (profile == null) {
                    return authenticationFailure(actionContext);
                } else {
                    saveUserProfile(profile, actionContext);
                    return authenticationSuccess(profile, actionContext);
                }
            }

        }).recover(new Function<Throwable, Result>() {

            @Override
            public Result apply(Throwable a) throws Throwable {
                if (a instanceof RequiresHttpAction) {
                    RequiresHttpAction e = (RequiresHttpAction) a;
                    return requireActionToResult(e.getCode(), actionContext);
                } else {
                    logger.error("Unexpected error", a);
                    throw a;
                }
            }

        });

    }

    /**
     * Retrieve user profile either by looking in the session or trying to authenticate directly
     * if stateless web service.
     * 
     * @param actionContext
     * @return
     */
    protected Promise<CommonProfile> retrieveUserProfile(final ActionContext actionContext) {
        if (isStateless(actionContext)) {
            return authenticate(actionContext);
        } else {
            return Promise.promise(new Function0<CommonProfile>() {

                @Override
                public CommonProfile apply() {
                    final CommonProfile profile = StorageHelper.getProfile(actionContext.getSessionId());
                    logger.debug("profile : {}", profile);
                    return profile;
                }
            });
        }

    }

    /**
     * Default authentication failure strategy which generates an unauthorized page if stateless web service
     * or redirect to the authentication provider after saving the original url.
     * 
     * @param actionContext
     * @return
     */
    protected Promise<Result> authenticationFailure(final ActionContext actionContext) {

        return Promise.promise(new Function0<Result>() {
            @Override
            public Result apply() throws RequiresHttpAction {
                if (isStateless(actionContext)) {
                    return unauthorized(Config.getErrorPage401()).as(HttpConstants.HTML_CONTENT_TYPE);
                } else {
                    // no authentication tried -> redirect to provider
                    // keep the current url
                    saveOriginalUrl(actionContext);
                    // compute and perform the redirection
                    return redirectToIdentityProvider(actionContext);
                }
            }
        });
    }

    /**
     * Save the user profile in session or attach it to the request if stateless web service.
     * 
     * @param profile
     * @param actionContext
     */
    protected void saveUserProfile(CommonProfile profile, final ActionContext actionContext) {
        if (isStateless(actionContext)) {
            actionContext.getCtx().args.put(Pac4jConstants.USER_PROFILE, profile);
        } else {
            StorageHelper.saveProfile(actionContext.getSessionId(), profile);
        }
    }

    /**
     * Default authentication success strategy which forward to the next action if the user
     * has access or returns an access denied error otherwise.
     * 
     * @param profile
     * @param actionContext
     * @return
     * @throws Throwable
     */
    protected Promise<Result> authenticationSuccess(CommonProfile profile, final ActionContext actionContext)
            throws Throwable {

        if (hasAccess(profile, actionContext)) {
            return delegate.call(actionContext.getCtx());
        } else {
            return Promise.promise(new Function0<Result>() {
                @Override
                public Result apply() {
                    return forbidden(Config.getErrorPage403()).as(HttpConstants.HTML_CONTENT_TYPE);
                }
            });
        }
    }

    /**
     * Authenticates the current request by getting the credentials and the corresponding user profile.
     * 
     * @param actionContext
     * @return
     */
    protected Promise<CommonProfile> authenticate(final ActionContext actionContext) {

        return Promise.promise(new Function0<CommonProfile>() {

            @Override
            public CommonProfile apply() throws RequiresHttpAction {
                final Client client = Config.getClients().findClient(actionContext.getClientName());
                logger.debug("client : {}", client);

                final Credentials credentials;
                credentials = client.getCredentials(actionContext.getWebContext());
                logger.debug("credentials : {}", credentials);

                // get user profile
                CommonProfile profile = (CommonProfile) client.getUserProfile(credentials,
                        actionContext.getWebContext());
                logger.debug("profile : {}", profile);

                return profile;
            }

        });
    }

    /**
     * Returns true if the user defined by the profile has access to the underlying resource
     * depending on the requireAnyRole and requireAllRoles fields.
     * 
     * @param profile
     * @param actionContext
     * @return
     */
    protected boolean hasAccess(CommonProfile profile, final ActionContext actionContext) {

        return profile.hasAccess(actionContext.getRequireAnyRole(), actionContext.getRequireAllRoles());
    }

    /**
     * Save the requested url in session if the request is not Ajax.
     * 
     * @param actionContext
     */
    protected void saveOriginalUrl(final ActionContext actionContext) {
        if (!isAjaxRequest(actionContext)) {
            // requested url to save
            final String requestedUrlToSave = CallbackController.defaultUrl(actionContext.getTargetUrl(), actionContext
                    .getRequest().uri());
            logger.debug("requestedUrlToSave : {}", requestedUrlToSave);
            StorageHelper.saveRequestedUrl(actionContext.getSessionId(), actionContext.getClientName(),
                    requestedUrlToSave);
        }
    }

    /**
     * Retrieve the requested url from the session.
     * 
     * @param actionContext
     * @return
     */
    protected String retrieveOriginalUrl(final ActionContext actionContext) {
        return StorageHelper.getRequestedUrl(actionContext.getSessionId(), actionContext.getClientName());
    }

    /**
     * Is it a request Ajax?
     * 
     * @param actionContext
     * @return
     */
    protected boolean isAjaxRequest(ActionContext actionContext) {
        return actionContext.isAjax();
    }

    /**
     * Is it a stateless authentication flow? 
     * 
     * @param actionContext
     * @return
     */
    protected boolean isStateless(final ActionContext actionContext) {
        return actionContext.isStateless();
    }

    private Result redirectToIdentityProvider(final ActionContext actionContext) throws RequiresHttpAction {
        Client<Credentials, CommonProfile> client = Config.getClients().findClient(actionContext.getClientName());
        RedirectAction action = ((BaseClient) client).getRedirectAction(actionContext.getWebContext(), true,
                isAjaxRequest(actionContext));
        logger.debug("redirectAction : {}", action);
        return toResult(action);
    }

    private Result toResult(RedirectAction action) {
        switch (action.getType()) {
        case REDIRECT:
            return redirect(action.getLocation());
        case SUCCESS:
            return ok(action.getContent()).as(HttpConstants.HTML_CONTENT_TYPE);
        default:
            throw new TechnicalException("Unsupported RedirectAction type " + action.getType());
        }
    }

    private Result requireActionToResult(int code, ActionContext actionContext) {
        // requires some specific HTTP action
        logger.debug("requires HTTP action : {}", code);
        if (code == HttpConstants.UNAUTHORIZED) {
            return unauthorized(Config.getErrorPage401()).as(HttpConstants.HTML_CONTENT_TYPE);
        } else if (code == HttpConstants.FORBIDDEN) {
            return forbidden(Config.getErrorPage403()).as(HttpConstants.HTML_CONTENT_TYPE);
        } else if (code == HttpConstants.TEMP_REDIRECT) {
            return redirect(actionContext.getWebContext().getResponseLocation());
        } else if (code == HttpConstants.OK) {
            final String content = actionContext.getWebContext().getResponseContent();
            logger.debug("render : {}", content);
            return ok(content).as(HttpConstants.HTML_CONTENT_TYPE);
        }
        final String message = "Unsupported HTTP action : " + code;
        logger.error(message);
        throw new TechnicalException(message);
    }

}
