/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.isis.viewer.wicket.viewer.integration.wicket;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.wicket.Session;
import org.apache.wicket.authroles.authentication.AuthenticatedWebSession;
import org.apache.wicket.authroles.authorization.strategies.role.Roles;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.cycle.RequestCycle;

import org.apache.isis.applib.clock.Clock;
import org.apache.isis.applib.services.session.SessionLoggingService;
import org.apache.isis.core.commons.authentication.AuthenticationSession;
import org.apache.isis.core.runtime.authentication.AuthenticationManager;
import org.apache.isis.core.runtime.authentication.AuthenticationRequest;
import org.apache.isis.core.runtime.authentication.AuthenticationRequestPassword;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.core.runtime.system.session.IsisSessionFactory;
import org.apache.isis.viewer.wicket.model.models.BookmarkedPagesModel;
import org.apache.isis.viewer.wicket.ui.components.widgets.breadcrumbs.BreadcrumbModel;
import org.apache.isis.viewer.wicket.ui.components.widgets.breadcrumbs.BreadcrumbModelProvider;
import org.apache.isis.viewer.wicket.ui.pages.BookmarkedPagesModelProvider;

/**
 * Viewer-specific implementation of {@link AuthenticatedWebSession}, which
 * delegates to the Isis' configured {@link AuthenticationManager}, and which
 * also tracks thread usage (so that multiple concurrent requests are all
 * associated with the same session).
 */
public class AuthenticatedWebSessionForIsis extends AuthenticatedWebSession implements BreadcrumbModelProvider, BookmarkedPagesModelProvider {

    private static final long serialVersionUID = 1L;

    public static final String USER_ROLE = "org.apache.isis.viewer.wicket.roles.USER";

    public static AuthenticatedWebSessionForIsis get() {
        return (AuthenticatedWebSessionForIsis) Session.get();
    }

    private final BookmarkedPagesModel bookmarkedPagesModel = new BookmarkedPagesModel();
    private final BreadcrumbModel breadcrumbModel = new BreadcrumbModel();
    
    private AuthenticationSession authenticationSession;

    public AuthenticatedWebSessionForIsis(final Request request) {
        super(request);
    }

    @Override
    public boolean authenticate(final String username, final String password) {
        AuthenticationRequest authenticationRequest = new AuthenticationRequestPassword(username, password);
        authenticationRequest.setRoles(Arrays.asList(USER_ROLE));
        authenticationSession = getAuthenticationManager().authenticate(authenticationRequest);
        if (authenticationSession != null) {
            log(SessionLoggingService.Type.LOGIN, username, null);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onInvalidate() {
        super.onInvalidate();

        SessionLoggingService.CausedBy causedBy = RequestCycle.get() != null
                ? SessionLoggingService.CausedBy.USER
                : SessionLoggingService.CausedBy.SESSION_EXPIRATION;

        String userName = null;
        if (authenticationSession != null) {
            userName = authenticationSession.getUserName();
        }

        // similar code in Restful Objects viewer (UserResourceServerside#logout)
        getAuthenticationManager().closeSession(authenticationSession);
        getIsisSessionFactory().closeSession();

        log(SessionLoggingService.Type.LOGOUT, userName, causedBy);
    }

    public AuthenticationSession getAuthenticationSession() {
        return authenticationSession;
    }

    @Override
    public Roles getRoles() {
        if (!isSignedIn()) {
            return null;
        }
        final List<String> roles = authenticationSession.getRoles();
        return new Roles(roles.toArray(new String[roles.size()]));
    }

    @Override
    public void detach() {
        breadcrumbModel.detach();
        super.detach();
    }

    // /////////////////////////////////////////////////
    // Breadcrumbs and Bookmarks support
    // /////////////////////////////////////////////////

    @Override
    public BreadcrumbModel getBreadcrumbModel() {
        return breadcrumbModel;
    }

    @Override
    public BookmarkedPagesModel getBookmarkedPagesModel() {
        return bookmarkedPagesModel;
    }


    // /////////////////////////////////////////////////
    // Dependencies
    // /////////////////////////////////////////////////

    protected AuthenticationManager getAuthenticationManager() {
        return getIsisSessionFactory().getAuthenticationManager();
    }

    // /////////////////////////////////////////////////
    // *Provider impl.
    // /////////////////////////////////////////////////
    
    private void log(
            final SessionLoggingService.Type type,
            final String username,
            final SessionLoggingService.CausedBy causedBy) {
        final SessionLoggingService sessionLoggingService = getSessionLoggingService();
        if (sessionLoggingService != null) {
            getIsisSessionFactory().doInSession(new Runnable() {
                    @Override
                    public void run() {
                        // use hashcode as session identifier, to avoid re-binding http sessions if using Session#getId()
                        int sessionHashCode = System.identityHashCode(AuthenticatedWebSessionForIsis.this);
                        sessionLoggingService.log(type, username, Clock.getTimeAsDateTime().toDate(), causedBy, Integer.toString(sessionHashCode));
                    }
                });
        }
    }

    protected SessionLoggingService getSessionLoggingService() {
        return getIsisSessionFactory().doInSession(new Callable<SessionLoggingService>() {
            @Override
            public SessionLoggingService call() throws Exception {

                // REVIEW: I strongly suspect that this doesn't need to be done in a session anymore
                return getIsisSessionFactory().getServicesInjector().lookupService(SessionLoggingService.class);
            }
        });
    }

    @Override
    public void replaceSession() {
        // do nothing here because this will lead to problems with Shiro
        // see https://issues.apache.org/jira/browse/ISIS-1018
    }


    IsisSessionFactory getIsisSessionFactory() {
        return IsisContext.getSessionFactory();
    }


}
