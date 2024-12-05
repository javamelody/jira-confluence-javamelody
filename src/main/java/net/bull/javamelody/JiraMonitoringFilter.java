/*
 * Copyright 2008-2019 by Emeric Vernat
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

import com.atlassian.annotations.security.UnrestrictedAccess;

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
 * Filter of monitoring JavaMelody without report.
 * @author Emeric Vernat
 */
@UnrestrictedAccess
public class JiraMonitoringFilter extends PluginMonitoringFilter {
	// valeur de DefaultAuthenticator.LOGGED_IN_KEY
	private static final String LOGGED_IN_KEY = "seraph_defaultauthenticator_user";

	// initialisation ici et non dans la méthode init, car on ne sait pas très
	// bien quand la méthode init serait appelée dans les systèmes de plugins
	private final boolean jira = isJira();
	private final boolean confluence = isConfluence();
	private final boolean bamboo = isBamboo();
	private final boolean bitbucket = isBitbucket();

	/** {@inheritDoc} */
	@Override
	public String getApplicationType() {
		if (jira) {
			return "JIRA";
		} else if (confluence) {
			return "Confluence";
		} else if (bamboo) {
			return "Bamboo";
		} else if (bitbucket) {
			return "Bitbucket";
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
		} else if (bitbucket) {
			logForDebug("JavaMelody is monitoring Bitbucket");
		} else {
			logForDebug("JavaMelody is monitoring unknown");
		}

		// add atlassian maven public repository for atlassian sources
		final String mavenRepositories = System.getProperty("user.home")
				+ "/.m2/repository,http://repo1.maven.org/maven2,https://maven.artifacts.atlassian.com/";
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

			// monitoring page is done in JiraReportFilter for system admins, not here
			chain.doFilter(request, response);
			return;
		}

		putRemoteUserInSession(httpRequest);

		super.doFilter(request, response, chain);
	}

	private void putRemoteUserInSession(HttpServletRequest httpRequest) {
		final HttpSession session = httpRequest.getSession(false);
		if (session != null && session.getAttribute(SessionListener.SESSION_REMOTE_USER) == null) {
			// si session null, la session n'est pas encore créée (et ne le sera peut-être jamais),
			try {
				final Object user = session.getAttribute(LOGGED_IN_KEY);
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

	private static boolean isJira() {
		try {
			Class.forName("com.atlassian.jira.component.ComponentAccessor");
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

	private static boolean isBitbucket() {
		try {
			Class.forName("com.atlassian.bitbucket.user.UserService");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}
}
