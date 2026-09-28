/*******************************************************************************
 * Copyright (C) 2026 EGit contributors and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.credentials;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.egit.core.credentials.UserPasswordCredentials;
import org.eclipse.egit.core.internal.trace.GitTraceLocation;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS.ExecutionResult;
import org.eclipse.jgit.util.TemporaryBuffer;

/**
 * Obtains credentials by delegating to native git's configured credential
 * helpers (for example
 * <a href="https://github.com/git-ecosystem/git-credential-manager">Git
 * Credential Manager</a>) via {@code git credential fill}.
 * <p>
 * This enables authentication flows implemented by external helpers, such as
 * OAuth2 single sign-on, that EGit does not implement itself. If no credential
 * helper is configured, {@code git credential fill} yields no password and this
 * class returns {@code null} so that EGit falls back to its own credential
 * handling.
 */
class GitCredentialHelper {

	private GitCredentialHelper() {
		// Utility class
	}

	/**
	 * Whether a native credential helper can be consulted for the given URI.
	 * Only HTTP(S) URIs are supported; other schemes (e.g. SSH) use different
	 * authentication mechanisms.
	 *
	 * @param uri
	 *            to check
	 * @return {@code true} if a credential helper may be able to serve the URI
	 */
	static boolean canHandle(URIish uri) {
		String scheme = uri.getScheme();
		return ("http".equals(scheme) || "https".equals(scheme)) //$NON-NLS-1$ //$NON-NLS-2$
				&& uri.getHost() != null && !uri.getHost().isEmpty();
	}

	/**
	 * Asks native git's configured credential helpers for credentials for the
	 * given URI by running {@code git credential fill}.
	 *
	 * @param uri
	 *            to obtain credentials for
	 * @param username
	 *            an already known user name, or {@code null}
	 * @return the credentials provided by a helper, or {@code null} if no helper
	 *         is configured or none could provide a password
	 */
	static UserPasswordCredentials fill(URIish uri, String username) {
		if (!canHandle(uri)) {
			return null;
		}
		String request = buildRequest(uri, username);

		ProcessBuilder process = new ProcessBuilder();
		process.command("git", "credential", "fill"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		process.directory(FS.DETECTED.userHome());
		// Never let git prompt on a terminal; if no helper answers we fall back
		// to EGit's own credential handling instead of blocking.
		process.environment().put("GIT_TERMINAL_PROMPT", "0"); //$NON-NLS-1$ //$NON-NLS-2$

		ExecutionResult result = null;
		try (InputStream in = new ByteArrayInputStream(
				request.getBytes(StandardCharsets.UTF_8))) {
			result = FS.DETECTED.execute(process, in);
			if (result.getRc() != 0) {
				return null;
			}
			return parse(result.getStdout());
		} catch (IOException | InterruptedException e) {
			if (GitTraceLocation.CORE.isActive()) {
				GitTraceLocation.getTrace().trace(
						GitTraceLocation.CORE.getLocation(),
						"git credential fill failed", e); //$NON-NLS-1$
			}
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
		} finally {
			if (result != null) {
				if (result.getStdout() != null) {
					result.getStdout().destroy();
				}
				if (result.getStderr() != null) {
					result.getStderr().destroy();
				}
			}
		}
	}

	private static String buildRequest(URIish uri, String username) {
		StringBuilder request = new StringBuilder();
		request.append("protocol=").append(uri.getScheme()).append('\n'); //$NON-NLS-1$
		String host = uri.getHost();
		if (uri.getPort() > 0) {
			host = host + ':' + uri.getPort();
		}
		request.append("host=").append(host).append('\n'); //$NON-NLS-1$
		String path = uri.getPath();
		if (path != null) {
			while (path.startsWith("/")) { //$NON-NLS-1$
				path = path.substring(1);
			}
			if (!path.isEmpty()) {
				request.append("path=").append(path).append('\n'); //$NON-NLS-1$
			}
		}
		if (username != null && !username.isEmpty()) {
			request.append("username=").append(username).append('\n'); //$NON-NLS-1$
		}
		request.append('\n');
		return request.toString();
	}

	private static UserPasswordCredentials parse(TemporaryBuffer stdout)
			throws IOException {
		String user = null;
		String password = null;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				stdout.openInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int eq = line.indexOf('=');
				if (eq < 0) {
					continue;
				}
				String key = line.substring(0, eq);
				String value = line.substring(eq + 1);
				if ("username".equals(key)) { //$NON-NLS-1$
					user = value;
				} else if ("password".equals(key)) { //$NON-NLS-1$
					password = value;
				}
			}
		}
		if (password == null || password.isEmpty()) {
			return null;
		}
		return new UserPasswordCredentials(user == null ? "" : user, //$NON-NLS-1$
				password);
	}
}
