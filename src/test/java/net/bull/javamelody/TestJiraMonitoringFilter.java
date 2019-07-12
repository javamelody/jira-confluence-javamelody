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
package net.bull.javamelody; // NOPMD

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.bull.javamelody.internal.common.Parameters;
import net.bull.javamelody.internal.web.FilterServletOutputStream;

/**
 * Test unitaire de la classe JiraMonitoringFilter.
 * @author Emeric Vernat
 */
public class TestJiraMonitoringFilter { // NOPMD
	private static final String FILTER_NAME = "monitoring";
	private static final String CONTEXT_PATH = "/test";
	private FilterConfig config;
	private ServletContext context;
	private JiraMonitoringFilter jiraMonitoringFilter;

	/**
	 * Initialisation.
	 */
	@Before
	public void setUp() {
		// rq: pas setUpFirst ici car setUp est rappelée dans les méthodes
		tearDown();
		try {
			final Field field = MonitoringFilter.class.getDeclaredField("instanceCreated");
			field.setAccessible(true);
			field.set(null, false);
		} catch (final IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (final NoSuchFieldException e) {
			throw new IllegalStateException(e);
		}
		config = createNiceMock(FilterConfig.class);
		context = createNiceMock(ServletContext.class);
		expect(config.getServletContext()).andReturn(context).anyTimes();
		expect(config.getFilterName()).andReturn(FILTER_NAME).anyTimes();
		// anyTimes sur getInitParameter car TestJdbcDriver a pu fixer la propriété système à false
		expect(context.getInitParameter(
				Parameters.PARAMETER_SYSTEM_PREFIX + Parameter.DISABLED.getCode())).andReturn(null)
						.anyTimes();
		expect(config.getInitParameter(Parameter.DISABLED.getCode())).andReturn(null).anyTimes();
		expect(context.getMajorVersion()).andReturn(2).anyTimes();
		expect(context.getMinorVersion()).andReturn(5).anyTimes();
		expect(context.getServletContextName()).andReturn("test webapp").anyTimes();
		expect(context.getServerInfo()).andReturn("mockJetty").anyTimes();
		expect(context.getContextPath()).andReturn(CONTEXT_PATH).anyTimes();
		jiraMonitoringFilter = new JiraMonitoringFilter();
	}

	/**
	 * Finalisation.
	 */
	@After
	public void tearDown() {
		destroy();
	}

	private void destroy() {
		if (jiraMonitoringFilter != null) {
			jiraMonitoringFilter.destroy();
		}
	}

	/** Test.
	 * @throws ServletException e
	 * @throws IOException e */
	@Test
	public void testNoHttp() throws ServletException, IOException {
		// non http
		final FilterChain servletChain = createNiceMock(FilterChain.class);
		final ServletRequest servletRequest = createNiceMock(ServletRequest.class);
		final ServletResponse servletResponse = createNiceMock(ServletResponse.class);
		replay(config);
		replay(context);
		replay(servletRequest);
		replay(servletResponse);
		replay(servletChain);
		jiraMonitoringFilter.init(config);
		jiraMonitoringFilter.doFilter(servletRequest, servletResponse, servletChain);
		verify(config);
		verify(context);
		verify(servletRequest);
		verify(servletResponse);
		verify(servletChain);
	}

	/** Test.
	 * @throws ServletException e
	 * @throws IOException e */
	@Test
	public void testNoSession() throws ServletException, IOException {
		// pas de session
		doJiraFilter(null);
	}

	/** Test.
	 * @throws ServletException e
	 * @throws IOException e */
	@Test
	public void testValidSession() throws ServletException, IOException {
		// session valide dans PluginMonitoringFilter
		final HttpSession session = createNiceMock(HttpSession.class);
		expect(session.getId()).andReturn("sessionId").anyTimes();
		doJiraFilter(session);
	}

	/** Test.
	 * @throws ServletException e
	 * @throws IOException e */
	@Test
	public void testSessionTimer() throws ServletException, IOException {
		System.setProperty("javamelody.resolution-seconds", "5");
		try {
			doJiraFilter(null);
		} finally {
			System.setProperty("javamelody.resolution-seconds", "60");
		}
	}

	/** Test.
	 * @throws ServletException e
	 * @throws IOException e */
	@Test
	public void testInvalidatedSession() throws ServletException, IOException {
		jiraMonitoringFilter.unregisterInvalidatedSessions();

		// session invalidée dans PluginMonitoringFilter
		final HttpSession session = createNiceMock(HttpSession.class);
		expect(session.getId()).andReturn("sessionId2").anyTimes();
		expect(session.getCreationTime()).andThrow(new IllegalStateException("invalidated"))
				.anyTimes();
		doJiraFilter(session);
	}

	private void doJiraFilter(HttpSession session) throws IOException, ServletException {
		final HttpServletRequest request = createNiceMock(HttpServletRequest.class);
		expect(request.getRequestURI()).andReturn("/test/monitoring").anyTimes();
		expect(request.getContextPath()).andReturn(CONTEXT_PATH).anyTimes();
		expect(request.getHeaders("Accept-Encoding"))
				.andReturn(Collections.enumeration(Arrays.asList("text/html"))).anyTimes();
		if (session != null) {
			expect(request.isRequestedSessionIdValid()).andReturn(Boolean.TRUE).anyTimes();
			expect(request.getSession(false)).andReturn(session).anyTimes();
		}
		final HttpServletResponse response = createNiceMock(HttpServletResponse.class);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		expect(response.getOutputStream()).andReturn(new FilterServletOutputStream(output))
				.anyTimes();
		final StringWriter stringWriter = new StringWriter();
		expect(response.getWriter()).andReturn(new PrintWriter(stringWriter)).anyTimes();
		final FilterChain chain = createNiceMock(FilterChain.class);

		replay(config);
		replay(context);
		replay(request);
		replay(response);
		replay(chain);
		if (session != null) {
			replay(session);
		}
		jiraMonitoringFilter.init(config);
		jiraMonitoringFilter.doFilter(request, response, chain);
		verify(config);
		verify(context);
		verify(request);
		verify(response);
		verify(chain);
		if (session != null) {
			verify(session);
		}
	}
}
