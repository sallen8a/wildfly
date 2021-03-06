package org.wildfly.extension.undertow.security.jaspi;

import io.undertow.security.api.AuthenticationMechanism;
import io.undertow.security.api.NotificationReceiver;
import io.undertow.security.api.SecurityContext;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.IdentityManager;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.AttachmentKey;

import org.jboss.security.SecurityContextAssociation;
import org.jboss.security.auth.callback.JBossCallbackHandler;
import org.jboss.security.auth.message.GenericMessageInfo;
import org.jboss.security.plugins.auth.JASPIServerAuthenticationManager;
import org.wildfly.extension.undertow.security.AccountImpl;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.security.auth.callback.JASPICallbackHandler;
import org.jboss.security.identity.Role;
import org.jboss.security.identity.RoleGroup;
import static org.wildfly.extension.undertow.UndertowLogger.ROOT_LOGGER;
import static org.wildfly.extension.undertow.UndertowMessages.MESSAGES;

/**
 * <p>
 * {@link AuthenticationMechanism} implementation that enables JASPI-based authentication.
 * </p>
 *
 * @author Pedro Igor
 * @author <a href="mailto:sguilhen@redhat.com">Stefan Guilhen</a>
 */
public class JASPIAuthenticationMechanism implements AuthenticationMechanism {

    private static final String JASPI_HTTP_SERVLET_LAYER = "HttpServlet";
    private static final String MECHANISM_NAME = "JASPIC";
    private static final String JASPI_AUTH_TYPE = "javax.servlet.http.authType";
    private static final String JASPI_REGISTER_SESSION = "javax.servlet.http.registerSession";

    public static final AttachmentKey<HttpServerExchange> HTTP_SERVER_EXCHANGE_ATTACHMENT_KEY = AttachmentKey.create(HttpServerExchange.class);
    public static final AttachmentKey<SecurityContext> SECURITY_CONTEXT_ATTACHMENT_KEY = AttachmentKey.create(SecurityContext.class);

    private final String securityDomain;

    public JASPIAuthenticationMechanism(final String securityDomain) {
        this.securityDomain = securityDomain;
    }

    @Override
    public AuthenticationMechanismOutcome authenticate(final HttpServerExchange exchange, final SecurityContext sc) {
        final JASPISecurityContext jaspiSecurityContext = new JASPISecurityContext(sc);
        final ServletRequestContext requestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        final JASPIServerAuthenticationManager sam = createJASPIAuthenticationManager();
        final GenericMessageInfo messageInfo = createMessageInfo(exchange, jaspiSecurityContext);
        final String applicationIdentifier = buildApplicationIdentifier(requestContext);
        final JASPICallbackHandler cbh = new JASPICallbackHandler();

        ROOT_LOGGER.debugf("validateRequest for layer [%s] and applicationContextIdentifier [%s]", JASPI_HTTP_SERVLET_LAYER, applicationIdentifier);

        AuthenticationMechanismOutcome outcome = AuthenticationMechanismOutcome.NOT_AUTHENTICATED;
        Account account = null;

        boolean isValid = sam.isValid(messageInfo, new Subject(), JASPI_HTTP_SERVLET_LAYER, applicationIdentifier, cbh);

        if (isValid) {
            // The CBH filled in the JBOSS SecurityContext, we need to create an Undertow account based on that
            org.jboss.security.SecurityContext jbossSct = SecurityActions.getSecurityContext();
            account = createAccount(jbossSct);
        }

        if (isValid && account != null) {
            outcome = AuthenticationMechanismOutcome.AUTHENTICATED;

            String type = (String) messageInfo.getMap().get(JASPI_AUTH_TYPE);
            Object registerObj = messageInfo.getMap().get(JASPI_REGISTER_SESSION);
            boolean cache = jaspiSecurityContext.isCachingRequired();
            if(registerObj != null && (registerObj instanceof String)) {
                cache = Boolean.valueOf((String)registerObj);
            }
            sc.authenticationComplete(account, type != null ? type : jaspiSecurityContext.getAuthType(), cache);
        } else if (isValid && account == null && !isMandatory(requestContext)) {
            outcome = AuthenticationMechanismOutcome.NOT_ATTEMPTED;
        } else {
            outcome = AuthenticationMechanismOutcome.NOT_AUTHENTICATED;
            sc.authenticationFailed("JASPI authentication failed.", MECHANISM_NAME);
        }

        // A SAM can wrap the HTTP request/response objects - update the servlet request context with the values found in the message info.
        ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        servletRequestContext.setServletRequest((HttpServletRequest) messageInfo.getRequestMessage());
        servletRequestContext.setServletResponse((HttpServletResponse) messageInfo.getResponseMessage());

        secureResponse(exchange, sc, sam, messageInfo, cbh);

        return outcome;

    }

    @Override
    public ChallengeResult sendChallenge(final HttpServerExchange exchange, final SecurityContext securityContext) {
        return new ChallengeResult(true);
    }

    private boolean isSecureResponse(final ServletRequestContext attachment, final SecurityContext securityContext) {
        return !wasAuthExceptionThrown();
    }

    private boolean wasAuthExceptionThrown() {
        return SecurityContextAssociation.getSecurityContext().getData().get(AuthException.class.getName()) != null;
    }

    private JASPIServerAuthenticationManager createJASPIAuthenticationManager() {
        return new JASPIServerAuthenticationManager(this.securityDomain, new JBossCallbackHandler());
    }

    private String buildApplicationIdentifier(final ServletRequestContext attachment) {
        ServletRequest servletRequest = attachment.getServletRequest();
        return servletRequest.getLocalName() + " " + servletRequest.getServletContext().getContextPath();
    }

    private GenericMessageInfo createMessageInfo(final HttpServerExchange exchange, final SecurityContext securityContext) {
        ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);

        GenericMessageInfo messageInfo = new GenericMessageInfo();

        messageInfo.setRequestMessage(servletRequestContext.getServletRequest());
        messageInfo.setResponseMessage(servletRequestContext.getServletResponse());

        messageInfo.getMap().put("javax.security.auth.message.MessagePolicy.isMandatory", isMandatory(servletRequestContext).toString());

        // additional context data, useful to provide access to Undertow resources during the modules processing
        messageInfo.getMap().put(SECURITY_CONTEXT_ATTACHMENT_KEY, securityContext);
        messageInfo.getMap().put(HTTP_SERVER_EXCHANGE_ATTACHMENT_KEY, exchange);

        return messageInfo;
    }

    private Account createAccount(final org.jboss.security.SecurityContext jbossSct) {
        if (jbossSct == null) {
            throw MESSAGES.nullParamter("org.jboss.security.SecurityContext");
        }

        Principal userPrincipal = jbossSct.getUtil().getUserPrincipal();
        if (userPrincipal == null) {
            return null;
        }

        Set<String> stringRoles = new HashSet<String>();
        RoleGroup roleGroup = jbossSct.getUtil().getRoles();
        if (roleGroup != null) {
            for (Role role : roleGroup.getRoles()) {
                stringRoles.add(role.getRoleName());
            }
        }

        Object credential = jbossSct.getUtil().getCredential();

        return new AccountImpl(userPrincipal, stringRoles, credential);
    }

    private void secureResponse(final HttpServerExchange exchange, final SecurityContext securityContext, final JASPIServerAuthenticationManager sam, final GenericMessageInfo messageInfo, final JASPICallbackHandler cbh) {
        // we add the a response listener to properly invoke the secureResponse, after processing the destination
        exchange.addExchangeCompleteListener(new ExchangeCompletionListener() {
            @Override
            public void exchangeEvent(final HttpServerExchange exchange, final NextListener nextListener) {
                ServletRequestContext requestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
                String applicationIdentifier = buildApplicationIdentifier(requestContext);

                if (isSecureResponse(requestContext, securityContext)) {
                    ROOT_LOGGER.debugf("secureResponse for layer [%s] and applicationContextIdentifier [%s].", JASPI_HTTP_SERVLET_LAYER, applicationIdentifier);
                    sam.secureResponse(messageInfo, new Subject(), JASPI_HTTP_SERVLET_LAYER, applicationIdentifier, cbh);

                    // A SAM can unwrap the HTTP request/response objects - update the servlet request context with the values found in the message info.
                    ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
                    servletRequestContext.setServletRequest((HttpServletRequest) messageInfo.getRequestMessage());
                    servletRequestContext.setServletResponse((HttpServletResponse) messageInfo.getResponseMessage());
                }
                nextListener.proceed();
            }
        });
    }

    /**
     * <p>The authentication is mandatory if the servlet has http constraints (eg.: {@link
     * javax.servlet.annotation.HttpConstraint}).</p>
     *
     * @param attachment
     * @return
     */
    // This information is already present in (undertow) SecurityContext, but there is no getter for it, so we cannot reuse it
    private Boolean isMandatory(final ServletRequestContext attachment) {
        return attachment.getCurrentServlet() != null
                && attachment.getCurrentServlet().getManagedServlet() != null
                && attachment.getCurrentServlet().getManagedServlet().getServletInfo() != null
                && attachment.getCurrentServlet().getManagedServlet().getServletInfo().getServletSecurityInfo() != null
                && attachment.getCurrentServlet().getManagedServlet().getServletInfo().getServletSecurityInfo().getRolesAllowed() != null
                && !attachment.getCurrentServlet().getManagedServlet().getServletInfo().getServletSecurityInfo().getRolesAllowed().isEmpty();
    }

    private static final class JASPISecurityContext implements SecurityContext {

        private final SecurityContext delegate;
        private boolean cachingRequired = false;
        private String authType;

        private JASPISecurityContext(SecurityContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean authenticate() {
            return delegate.authenticate();
        }

        @Override
        public boolean login(String username, String password) {
            return delegate.login(username, password);
        }

        @Override
        public void logout() {
            delegate.logout();
        }

        @Override
        public void setAuthenticationRequired() {
            delegate.setAuthenticationRequired();
        }

        @Override
        public void addAuthenticationMechanism(AuthenticationMechanism mechanism) {
            delegate.addAuthenticationMechanism(mechanism);
        }

        @Override
        public List<AuthenticationMechanism> getAuthenticationMechanisms() {
            return delegate.getAuthenticationMechanisms();
        }

        @Override
        public boolean isAuthenticated() {
            return delegate.isAuthenticated();
        }

        @Override
        public Account getAuthenticatedAccount() {
            return delegate.getAuthenticatedAccount();
        }

        @Override
        public String getMechanismName() {
            return delegate.getMechanismName();
        }

        @Override
        public IdentityManager getIdentityManager() {
            return delegate.getIdentityManager();
        }

        @Override
        public void authenticationComplete(Account account, String mechanismName, boolean cachingRequired) {
            //noop, we don't want the auth method that we have delegated too to actually call this method
            this.cachingRequired = cachingRequired;
            this.authType = mechanismName;
        }

        @Override
        public void authenticationFailed(String message, String mechanismName) {
            delegate.authenticationFailed(message, mechanismName);
        }

        @Override
        public void registerNotificationReceiver(NotificationReceiver receiver) {
            delegate.registerNotificationReceiver(receiver);
        }

        @Override
        public void removeNotificationReceiver(NotificationReceiver receiver) {
            delegate.removeNotificationReceiver(receiver);
        }

        private boolean isCachingRequired() {
            return cachingRequired;
        }

        private String getAuthType() {
            return authType;
        }
    }
}
