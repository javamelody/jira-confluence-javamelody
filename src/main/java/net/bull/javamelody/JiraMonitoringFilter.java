/*
 * Copyright 2008-2017 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Filter of monitoring JavaMelody for JIRA/Bamboo/Confluence with security
 * check for system administrator.
 * @author Emeric Vernat
 */
public class JiraMonitoringFilter extends PluginMonitoringFilter {
	private static final boolean PLUGIN_AUTHENTICATION_DISABLED = Parameter.PLUGIN_AUTHENTICATION_DISABLED
			.getValueAsBoolean();
	// valeur de com.atlassian.jira.security.Permissions.SYSTEM_ADMIN
	private static final int SYSTEM_ADMIN = 44;
	// valeur de DefaultAuthenticator.LOGGED_IN_KEY
	private static final String LOGGED_IN_KEY = "seraph_defaultauthenticator_user";
	private static final List<String> JIRA_USER_CLASSES = Arrays.asList(
			// since JIRA 6, but exists in JIRA 5.2:
			"com.atlassian.jira.user.ApplicationUser",
			// since JIRA 5:
			"com.atlassian.crowd.embedded.api.User",
			// before JIRA 5:
			"com.opensymphony.user.User");

	// initialisation ici et non dans la méthode init, car on ne sait pas très
	// bien
	// quand la méthode init serait appelée dans les systèmes de plugins
	private final boolean jira = isJira();
	private final boolean confluence = isConfluence();
	private final boolean bamboo = isBamboo();

	private final boolean jiraHasProperApplicationUserSupport = jira && hasJirasPermissionManagerApplicationUserSupport();

	private boolean confluenceGetUserByNameExists = true; // on suppose true au
															// départ

	/** {@inheritDoc} */
	@Override
	public String getApplicationType() {
		if (jira) {
			return "JIRA";
		} else if (confluence) {
			return "Confluence";
		} else if (bamboo) {
			return "Bamboo";
		}
		return "?";
	}

	/** {@inheritDoc} */
	@Override
	public void init(FilterConfig config) throws ServletException {
		super.init(config);

		if (jira) {
			logForDebug("JavaMelody is monitoring JIRA");
		} else if (confluence) {
			logForDebug("JavaMelody is monitoring Confluence");
		} else if (bamboo) {
			logForDebug("JavaMelody is monitoring Bamboo");
		} else {
			logForDebug(
					"JavaMelody is monitoring unknown, access to monitoring reports is not secured by JavaMelody");
		}
		if (PLUGIN_AUTHENTICATION_DISABLED) {
			logForDebug("Authentication for monitoring reports has been disabled");
		}

		// add atlassian maven public repository for atlassian sources
		final String mavenRepositories = System.getProperty("user.home")
				+ "/.m2/repository,http://repo1.maven.org/maven2,https://maven.atlassian.com/content/repositories/public/";
		Parameter.MAVEN_REPOSITORIES.setValue(mavenRepositories);

		final String analyticsDisabled = "javamelody.analytics-disabled";
		if (System.getProperty(analyticsDisabled) != null
				|| config.getServletContext().getInitParameter(analyticsDisabled) != null) {
			System.setProperty("javamelody.analytics-id", "disabled");
		}
	}

	/** {@inheritDoc} */
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest)) {
			super.doFilter(request, response, chain);
			return;
		}
		final HttpServletRequest httpRequest = (HttpServletRequest) request;
		final HttpServletResponse httpResponse = (HttpServletResponse) response;

		if (httpRequest.getRequestURI().equals(getMonitoringUrl(httpRequest))) {
			if (isRumMonitoring(httpRequest, httpResponse)) {
				return;
			}

			if (hasNotPermission(httpRequest, httpResponse)) {
				return;
			}
		}

		putRemoteUserInSession(httpRequest);

		super.doFilter(request, response, chain);
	}

	private void putRemoteUserInSession(HttpServletRequest httpRequest) {
		final HttpSession session = httpRequest.getSession(false);
		if (session != null && session.getAttribute(SessionListener.SESSION_REMOTE_USER) == null) {
			// si session null, la session n'est pas encore créée (et ne le sera
			// peut-être jamais),
			try {
				final Object user = getUser(session);
				// objet utilisateur, peut être null
				if (user instanceof Principal) {
					final String remoteUser = ((Principal) user).getName();
					session.setAttribute(SessionListener.SESSION_REMOTE_USER, remoteUser);
				}
			} catch (final Exception e) {
				// tant pis
				return;
			}
		}
	}

	private boolean hasNotPermission(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws IOException {
		return !PLUGIN_AUTHENTICATION_DISABLED
				&& (jira && !checkJiraAdminPermission(httpRequest, httpResponse)
						|| confluence && !checkConfluenceAdminPermission(httpRequest, httpResponse)
						|| bamboo && !checkBambooAdminPermission(httpRequest, httpResponse));
	}

	private boolean checkJiraAdminPermission(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws IOException {
		// only the administrator can view the monitoring report
		final Object user = getUser(httpRequest);
		if (user == null) {
			// si non authentifié, on redirige vers la page de login en
			// indiquant la page
			// d'origine (sans le contexte) à afficher après le login
			final String destination = getMonitoringUrl(httpRequest)
					.substring(httpRequest.getContextPath().length());
			httpResponse.sendRedirect("login.jsp?os_destination=" + destination);
			return false;
		}
		if (!hasJiraSystemAdminPermission(user)) {
			// si authentifié mais sans la permission system admin, alors
			// Forbidden
			httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden access");
			return false;
		}
		return true;
	}

	private boolean checkConfluenceAdminPermission(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws IOException {
		// only the administrator can view the monitoring report
		final Object user = getUser(httpRequest);
		if (user == null) {
			// si non authentifié, on redirige vers la page de login en
			// indiquant la page
			// d'origine (sans le contexte) à afficher après le login
			final String destination = getMonitoringUrl(httpRequest)
					.substring(httpRequest.getContextPath().length());
			httpResponse.sendRedirect("login.action?os_destination=" + destination);
			return false;
		}
		if (!hasConfluenceAdminPermission(user)) {
			// si authentifié mais sans la permission system admin, alors
			// Forbidden
			httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden access");
			return false;
		}
		return true;
	}

	private boolean checkBambooAdminPermission(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws IOException {
		// only the administrator can view the monitoring report
		final Object user = getUser(httpRequest);
		if (user == null) {
			// si non authentifié, on redirige vers la page de login en
			// indiquant la page
			// d'origine (sans le contexte) à afficher après le login
			final String destination = getMonitoringUrl(httpRequest)
					.substring(httpRequest.getContextPath().length());
			httpResponse.sendRedirect("userlogin!default.action?os_destination=" + destination);
			return false;
		}
		if (!hasBambooAdminPermission(user)) {
			// si authentifié mais sans la permission admin, alors Forbidden
			httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden access");
			return false;
		}
		return true;
	}

	private boolean hasJiraSystemAdminPermission(Object user) {
		try {
			final Class<?> componentAccessorClass = Class
					.forName("com.atlassian.jira.component.ComponentAccessor");
			// on travaille par réflexion car la compilation normale
			// introduirait une dépendance
			// trop compliquée et trop lourde à télécharger pour maven
			// Note : si getPermissionManager().hasPermission est supprimée,
			// il faudra utiliser getGlobalPermissionManager().hasPermission
			// (Since v6.2.5)
			final Object permissionManager = componentAccessorClass
					.getMethod("getPermissionManager").invoke(null);

			// "user" may not be of the correct class, e.g. when a custom authenticator sets a Principal
			// which is NOT an ApplicationUser.
			// for JIRA 6+, convert it to an ApplicationUser
			if (user instanceof Principal && jiraHasProperApplicationUserSupport) {
				final Object userManager = componentAccessorClass
						.getMethod("getUserManager").invoke(null);
				final String userName = ((Principal) user).getName();
				final Object applicationUser = userManager.getClass()
						.getMethod("getUserByName", String.class)
						.invoke(userManager, userName);

				final Class<?> applicationUserClass = Class.forName("com.atlassian.jira.user.ApplicationUser");
				return (Boolean) permissionManager.getClass()
						.getMethod("hasPermission", Integer.TYPE, applicationUserClass)
						.invoke(permissionManager, SYSTEM_ADMIN, applicationUser);
			}
			// otherwise try known user classes
			else {
				Exception firstException = null;
				// selon la version de JIRA, on essaye les différentes classes
				// possibles du user
				for (final String className : JIRA_USER_CLASSES) {
					try {
						final Class<?> userClass = Class.forName(className);
						return (Boolean) permissionManager.getClass()
								.getMethod("hasPermission", Integer.TYPE, userClass)
								.invoke(permissionManager, SYSTEM_ADMIN, user);
					} catch (final Exception e) {
						if (firstException == null) {
							firstException = e;
						}
					}
				}
				// aucune classe n'a fonctionné
				throw firstException;
			}
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
		// return user != null
		// &&
		// com.atlassian.jira.component.ComponentAccessor.getPermissionManager().hasPermission(
		// SYSTEM_ADMIN, (com.opensymphony.user.User) user);
	}

	private static boolean hasJirasPermissionManagerApplicationUserSupport() {
		try {
			final Class<?> componentAccessorClass = Class
					.forName("com.atlassian.jira.component.ComponentAccessor");
			final Object permissionManager = componentAccessorClass
					.getMethod("getPermissionManager").invoke(null);
			try {
				// since JIRA 5.1.1
				final Class<?> applicationUserClass = Class.forName("com.atlassian.jira.user.ApplicationUser");
				// since JIRA 6.0
				permissionManager.getClass().getMethod("hasPermission", Integer.TYPE, applicationUserClass);
				return true;
			} catch (final Exception e) {
				return false;
			}
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean hasConfluenceAdminPermission(Object user) {
		try {
			final Class<?> containerManagerClass = Class
					.forName("com.atlassian.spring.container.ContainerManager");
			final Class<?> userClass = Class.forName("com.atlassian.user.User");
			// on travaille par réflexion car la compilation normale
			// introduirait une dépendance
			// trop compliquée et trop lourde à télécharger pour maven
			final Object permissionManager = containerManagerClass
					.getMethod("getComponent", String.class).invoke(null, "permissionManager");
			final Boolean result = (Boolean) permissionManager.getClass()
					.getMethod("isConfluenceAdministrator", userClass)
					.invoke(permissionManager, user);
			return result;
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
		// return user != null
		// &&
		// com.atlassian.spring.container.ContainerManager.getComponent("permissionManager").
		// isConfluenceAdministrator((com.opensymphony.user.User) user);
	}

	private static boolean hasBambooAdminPermission(Object user) {
		try {
			final Class<?> containerManagerClass = Class
					.forName("com.atlassian.spring.container.ContainerManager");
			// on travaille par réflexion car la compilation normale
			// introduirait une dépendance
			// trop compliquée et trop lourde à télécharger pour maven
			final Object bambooPermissionManager = containerManagerClass
					.getMethod("getComponent", String.class)
					.invoke(null, "bambooPermissionManager");

			Boolean result;
			try {
				// since Bamboo 3.1 (issue 192):
				result = (Boolean) bambooPermissionManager.getClass()
						.getMethod("isSystemAdmin", String.class)
						.invoke(bambooPermissionManager, user.toString());
			} catch (final NoSuchMethodException e) {
				// before Bamboo 3.1 (issue 192):
				final Class<?> globalApplicationSecureObjectClass = Class
						.forName("com.atlassian.bamboo.security.GlobalApplicationSecureObject");
				final Object globalApplicationSecureObject = globalApplicationSecureObjectClass
						.getField("INSTANCE").get(null);
				result = (Boolean) bambooPermissionManager.getClass()
						.getMethod("hasPermission", String.class, String.class, Object.class)
						.invoke(bambooPermissionManager, user.toString(), "ADMIN",
								globalApplicationSecureObject);
			}
			return result;
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
		// return user != null
		// &&
		// com.atlassian.spring.container.ContainerManager.getComponent("bambooPermissionManager").
		// hasPermission(username, "ADMIN",
		// GlobalApplicationSecureObject.INSTANCE);
	}

	private Object getUser(HttpServletRequest httpRequest) {
		final HttpSession session = httpRequest.getSession(false);
		return getUser(session);
	}

	private Object getUser(HttpSession session) {
		// ceci fonctionne dans JIRA et dans Confluence (et Bamboo ?)
		if (session == null) {
			return null;
		}
		Object result = session.getAttribute(LOGGED_IN_KEY);
		if (confluence) {
			if (result != null && "com.atlassian.confluence.user.SessionSafePrincipal"
					.equals(result.getClass().getName())) {
				// since confluence 4.1.4 (or 4.1.?)
				final String userName = result.toString();
				// note: httpRequest.getRemoteUser() null in general
				try {
					final Class<?> containerManagerClass = Class
							.forName("com.atlassian.spring.container.ContainerManager");
					final Object userAccessor = containerManagerClass
							.getMethod("getComponent", String.class).invoke(null, "userAccessor");
					result = userAccessor.getClass().getMethod("getUser", String.class)
							.invoke(userAccessor, userName);
				} catch (final Exception e) {
					throw new IllegalStateException(e);
				}
			} else if (result instanceof Principal && confluenceGetUserByNameExists) {
				// since confluence 5.2 or 5.3
				final String userName = ((Principal) result).getName();
				try {
					final Class<?> containerManagerClass = Class
							.forName("com.atlassian.spring.container.ContainerManager");
					final Object userAccessor = containerManagerClass
							.getMethod("getComponent", String.class).invoke(null, "userAccessor");
					// getUser deprecated, use getUserByName as said in:
					// https://docs.atlassian.com/atlassian-confluence/5.3.1/com/atlassian/confluence/user/UserAccessor.html
					try {
						result = userAccessor.getClass().getMethod("getUserByName", String.class)
								.invoke(userAccessor, userName);
					} catch (final NoSuchMethodException e) {
						// getUserByName does not exist in old Confluence
						// versions (3.5.13 for example)
						confluenceGetUserByNameExists = false;
					}
				} catch (final Exception e) {
					throw new IllegalStateException(e);
				}
			}
		}
		return result;
	}

	private static boolean isJira() {
		try {
			Class.forName("com.atlassian.jira.ManagerFactory");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}

	private static boolean isConfluence() {
		try {
			Class.forName("com.atlassian.confluence.security.PermissionManager");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}

	private static boolean isBamboo() {
		try {
			Class.forName("com.atlassian.bamboo.security.BambooPermissionManager");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}
}
