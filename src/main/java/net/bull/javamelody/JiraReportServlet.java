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

/**
 * Servlet for report of JavaMelody for JIRA/Bamboo/Confluence with security check for system administrator.
 * @author Emeric Vernat
 */
@SystemAdminOnly
public class JiraReportServlet extends ReportServlet {
}
