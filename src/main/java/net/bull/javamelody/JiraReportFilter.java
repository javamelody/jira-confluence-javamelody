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

import com.atlassian.annotations.security.SystemAdminOnly;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Filter for report of JavaMelody for JIRA/Bamboo/Confluence with security check for system administrator.
 * @author Emeric Vernat
 */
@SystemAdminOnly
public class JiraReportFilter extends PluginMonitoringFilter {
	private ReportServlet reportServlet;

	/** {@inheritDoc} */
	@Override
	public void init(final FilterConfig config) throws ServletException {
		final ServletConfig servletConfig = new ServletConfig() {

			@Override
			public String getServletName() {
				return "ReportServlet";
			}

			@Override
			public ServletContext getServletContext() {
				return config.getServletContext();
			}

			@Override
			public String getInitParameter(String name) {
				return "";
			}

			@Override
			public Enumeration<String> getInitParameterNames() {
				return Collections.emptyEnumeration();
			}
		};
		this.reportServlet = new ReportServlet();
		this.reportServlet.init(servletConfig);
	}

	/** {@inheritDoc} */
	@Override
	public void destroy() {
		reportServlet = null;
	}

	/** {@inheritDoc} */
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest)) {
			chain.doFilter(request, response);
			return;
		}
		final HttpServletRequest httpRequest = (HttpServletRequest) request;
		final HttpServletResponse httpResponse = (HttpServletResponse) response;

		reportServlet.doGet(httpRequest, httpResponse);
	}
}
