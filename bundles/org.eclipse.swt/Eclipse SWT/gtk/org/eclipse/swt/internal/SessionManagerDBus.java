/*******************************************************************************
 * Copyright (c) 2019 Syntevo and others. All rights reserved.
 * The contents of this file are made available under the terms
 * of the GNU Lesser General Public License (LGPL) Version 2.1 that
 * accompanies this distribution (lgpl-v21.txt).  The LGPL is also
 * available at http://www.gnu.org/licenses/lgpl.html.  If the version
 * of the LGPL at http://www.gnu.org is different to the version of
 * the LGPL accompanying this distribution and there is any conflict
 * between the two license versions, the terms of the LGPL accompanying
 * this distribution shall govern.
 *
 * Contributors:
 *     Syntevo - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.internal;

import org.eclipse.swt.internal.gtk.OS;

import java.util.ArrayList;

/**
 * Communicates with session manager to receive logoff/shutdown events.
 *
 * GTK also has an implementation (see gtk_application_impl_dbus_startup)
 * However, it requires GtkApplication, and SWT doesn't use that.
 *
 * Current session manager clients can be seen in:
 *   dbus-send --print-reply --dest=org.gnome.SessionManager /org/gnome/SessionManager org.gnome.SessionManager.GetClients
 *
 * If you know clientObjectPath, you can send Stop signal with:
 *   dbus-send --print-reply --dest=org.gnome.SessionManager /org/gnome/SessionManager/ClientXX org.gnome.SessionManager.Client.Stop
 */
public class SessionManagerDBus {
	public interface IListener {
		/**
		 * Are you ready to exit?
		 *
		 * Time limit imposed by session manager is 1 second.
		 * Final cleanup should happen in stop().
		 * @return false to hint that you're not ready. Session manager can ignore the hint.
		 */
		boolean isReadyToExit();

		/**
		 * Perform final cleanup here.
		 *
		 * Please note that time limit imposed by session manager is 10 seconds.
		 */
		void stop();
	}

	private static class ShutdownHook extends Thread {
		private SessionManagerDBus parent;

		public ShutdownHook(SessionManagerDBus parent) {
			this.parent = parent;
		}

		public void run() {
			parent.stop();
		}

		public void install() {
			try {
				Runtime.getRuntime().addShutdownHook(this);
			} catch (IllegalArgumentException ex) {
				// Shouldn't happen
				ex.printStackTrace();
			} catch (IllegalStateException ex) {
				// Shouldn't happen
				ex.printStackTrace();
			} catch (SecurityException ex) {
				// That's pity, but not too much of a problem.
				// Client will stay registered, contributing to clutter a little bit.
			}
		}

		public void remove() {
			try {
				Runtime.getRuntime().removeShutdownHook(this);
			} catch (IllegalStateException ex) {
				// The virtual machine is already in the process of shutting down.
				// That's expected.
			} catch (SecurityException ex) {
				// Shouldn't happen if 'addShutdownHook' worked.
				ex.printStackTrace();
			}
		}
	}

	private ArrayList<IListener> listeners = new ArrayList<IListener>();
	private Callback g_signal_callback;
	private ShutdownHook shutdownHook = new ShutdownHook(this);
	private long sessionManagerProxy;
	private long clientProxy;
	private String clientObjectPath;
	private boolean isGnome;

	private static int dbusTimeoutMsec = 10000;

	public SessionManagerDBus() {
		// Allow to disable session manager, for example in case it conflicts with
		// session manager connection implemented in application itself.
		boolean isDisabled = System.getProperty("org.eclipse.swt.internal.SessionManagerDBus.disable") != null;
		if (isDisabled) return;

		start();
	}

	/**
	 * Subscribes display for session manager events.
	 *
	 * Display will receive SWT.Close and will be able to hint that the session should not end.
	 * Please note that time limit imposed by session manager is 1 second.
	 * Final cleanup should happen at Display.dispose().
	 *
	 * Display will be disposed before session ends, allowing final cleanup to happen.
	 * Please note that time limit imposed by session manager is 10 seconds.
	 */
	public void addListener(IListener listener) {
		listeners.add(listener);
	}

	public void removeListener(IListener listener) {
		listeners.remove(listener);
	}

	private boolean start() {
		if (!connectSessionManager() || !registerClient() || !connectClientSignal()) {
			stop();
			return false;
		}

		// Both XFCE and Gnome will automatically unregister client on exit.
		// However, to be on the correct side, we should also do it.
		// Shutdown hook is used, because there's no other exit callback in SWT.
		// Display.dispose() isn't good because there could be many displays.
		// Also, in theory Displays can be created and disposed multiple times.
		shutdownHook.install();

		return true;
	}

	private void stop() {
		if ((sessionManagerProxy != 0) && (clientObjectPath != null)) {
			long args = OS.g_variant_new(
					Converter.javaStringToCString("(o)"), //$NON-NLS-1$
					Converter.javaStringToCString(clientObjectPath));

			long [] error = new long [1];
			OS.g_dbus_proxy_call_sync(
					sessionManagerProxy,
					Converter.javaStringToCString("UnregisterClient"), //$NON-NLS-1$
					args,
					OS.G_DBUS_CALL_FLAGS_NONE,
					dbusTimeoutMsec,
					0,
					error);

			if (error[0] != 0) {
				System.err.format(
						"SWT SessionManagerDBus: Failed to UnregisterClient: %s%n",
						extractFreeGError(error[0]));
			}

			clientObjectPath = null;
		}

		if (clientProxy != 0) {
			OS.g_object_unref(clientProxy);
			clientProxy = 0;
		}

		if (sessionManagerProxy != 0) {
			OS.g_object_unref(sessionManagerProxy);
			sessionManagerProxy = 0;
		}

		if (g_signal_callback != null) {
			g_signal_callback.dispose();
			g_signal_callback = null;
		}

		shutdownHook.remove();
	}

	private void sendEndSessionResponse(boolean is_ok, String reason) {
		long args = OS.g_variant_new(
				Converter.javaStringToCString("(bs)"), //$NON-NLS-1$
				is_ok,
				Converter.javaStringToCString(reason));

		long [] error = new long [1];
		OS.g_dbus_proxy_call(
				clientProxy,
				Converter.javaStringToCString("EndSessionResponse"), //$NON-NLS-1$
				args,
				OS.G_DBUS_CALL_FLAGS_NONE,
				dbusTimeoutMsec,
				0,
				0,
				error);

		if (error[0] != 0) {
			System.err.format(
					"SWT SessionManagerDBus: Failed to EndSessionResponse: %s%n",
					extractFreeGError(error[0]));
		}
	}

	private boolean isReadyToExit() {
		boolean isReady = true;

		// Inform everyone even if someone is not ready.
		for (int i = 0; i < listeners.size(); i++) {
			IListener listener = listeners.get(i);
			isReady = isReady && listener.isReadyToExit();
		}

		return isReady;
	}

	private void handleStop() {
		for (int i = 0; i < listeners.size(); i++) {
			IListener listener = listeners.get(i);
			listener.stop();
		}
	}

	/**
	 * Receives events from session manager.
	 *
	 * Docs: https://developer.gnome.org/gio/stable/GDBusProxy.html#GDBusProxy-g-signal
	 * NOTE: Will be called through native callback.
	 * @see this.g_signal_callback
	 * @return Error string in case of error, null if successful.
	 */
	@SuppressWarnings("unused")
	private long g_signal_handler(long proxy, long sender_name, long signal_name, long parameters, long user_data) {
		String signalName = Converter.cCharPtrToJavaString(signal_name, false);

		switch (signalName) {
			case "QueryEndSession": //$NON-NLS-1$
				sendEndSessionResponse(isReadyToExit(), "");
				break;
			case "EndSession": //$NON-NLS-1$
				handleStop();
				// Only respond after we've done, or session can end while we're still working.
				// Even if we don't want the session to end, I don't think sending 'false' here can be of any help.
				sendEndSessionResponse(true, "");
				break;
			case "Stop":
				handleStop();
				break;
		}

		// DBus expects 'void', but to make it easier to use with 'Callback' I return 'long'.
		return 0;
	}

	private static String extractVariantTupleS(long variant) {
		long childVariant = OS.g_variant_get_child_value(variant, 0);
		long childString = OS.g_variant_get_string(childVariant, null);

		String result = Converter.cCharPtrToJavaString(childString, false);

		OS.g_variant_unref(childVariant);
		return result;
	}

	private static String extractFreeGError(long errorPtr) {
		long errorMessageC = OS.g_error_get_message(errorPtr);
		String errorMessageStr = Converter.cCharPtrToJavaString(errorMessageC, false);
		OS.g_error_free(errorPtr);
		return errorMessageStr;
	}

	/**
	 * Creates a connection to the session manager.
	 *
	 * Saves result to member variable when successful.
	 * @return Error string in case of error, null if successful.
	 */
	private String connectSessionManager(String dbusName, String objectPath, String interfaceName) {
		int sessionManagerFlags =
				OS.G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START |
				OS.G_DBUS_PROXY_FLAGS_DO_NOT_LOAD_PROPERTIES |
				OS.G_DBUS_PROXY_FLAGS_DO_NOT_CONNECT_SIGNALS;

		long [] error = new long [1];
		long proxy = OS.g_dbus_proxy_new_for_bus_sync(
				OS.G_BUS_TYPE_SESSION,
				sessionManagerFlags,
				0,
				Converter.javaStringToCString(dbusName),
				Converter.javaStringToCString(objectPath),
				Converter.javaStringToCString(interfaceName),
				0,
				error);

		// Proxy is usually created even for non-existent service names.
		if (proxy == 0) return extractFreeGError(error[0]);

		// Is the service actually present?
		long owner = OS.g_dbus_proxy_get_name_owner(proxy);
		if (owner == 0) {
			OS.g_object_unref(proxy);
			return "Service not present";
		}
		OS.g_free(owner);

		// Success
		sessionManagerProxy = proxy;
		return null;
	}

	private boolean connectSessionManager() {
		String errorGnome = connectSessionManager(
				"org.gnome.SessionManager",     //$NON-NLS-1$
				"/org/gnome/SessionManager",    //$NON-NLS-1$
				"org.gnome.SessionManager");	//$NON-NLS-1$

		if (errorGnome == null) {
			isGnome = true;
			return true;
		}

		String errorXCFE = connectSessionManager(
				"org.xfce.SessionManager",		//$NON-NLS-1$
				"/org/xfce/SessionManager",		//$NON-NLS-1$
				"org.xfce.Session.Manager");	//$NON-NLS-1$

		if (errorXCFE == null) {
			isGnome = false;
			return true;
		}

		System.err.format(
				"SWT SessionManagerDBus: Failed to connect to SessionManager (gnome: %s, xcfe: %s)%n",
				errorGnome,
				errorXCFE);

		return false;
	}

	/**
	 * Gets the value of 'DESKTOP_AUTOSTART_ID'.
	 *
	 * This environment variable is set by session manager if the
	 * application was auto started (because it is configured to run
	 * automatically for every session). The variable helps session
	 * manager to match autostart settings with actual applications.
	 *
	 * For applications that were not started automatically, the
	 * variable is expected to be absent.
	 *
	 * Once used, 'DESKTOP_AUTOSTART_ID' must not leak into child
	 * processes, or they will fail to 'RegisterClient'.
	 */
	private String claimDesktopAutostartID() {
		byte[] DESKTOP_AUTOSTART_ID = Converter.javaStringToCString("DESKTOP_AUTOSTART_ID");	//$NON-NLS-1$

		// NOTE: the returned pointer is not valid after g_unsetenv()
		long valueC = OS.g_getenv(DESKTOP_AUTOSTART_ID);
		if (valueC == 0) return null;
		String result = Converter.cCharPtrToJavaString(valueC, false);

		// Unset value, so it doesn't leak into child processes
		OS.g_unsetenv(DESKTOP_AUTOSTART_ID);

		return result;
	}

	/**
	 * Issues 'RegisterClient' dbus request to register with session manager.
	 *
	 * Saves result to member variable when successful.
	 * @return Error string in case of error, null if successful.
	 */
	private String registerClient(String appID, String clientStartupID) {
		long args = OS.g_variant_new(
				Converter.javaStringToCString("(ss)"),				//$NON-NLS-1$
				Converter.javaStringToCString(appID),
				Converter.javaStringToCString(clientStartupID));

		long [] error = new long [1];
		long clientInfo = OS.g_dbus_proxy_call_sync(
				sessionManagerProxy,
				Converter.javaStringToCString("RegisterClient"),	//$NON-NLS-1$
				args,
				OS.G_DBUS_CALL_FLAGS_NONE,
				dbusTimeoutMsec,
				0,
				error);

		if (clientInfo == 0) return extractFreeGError(error[0]);

		// Success
		clientObjectPath = extractVariantTupleS(clientInfo);
		OS.g_variant_unref(clientInfo);
		return null;
	}

	private boolean registerClient() {
		// This ID doesn't matter much, at least according to what I know.
		// Still, I decided to make it customizable for those who love identity.
		String appID = System.getProperty("org.eclipse.swt.internal.SessionManagerDBus.appID");	//$NON-NLS-1$
		if (appID == null) appID = "org.eclipse.swt.Application";	//$NON-NLS-1$

		// Applications are expected to register using value of
		// 'DESKTOP_AUTOSTART_ID' environment if it's present.
		String desktopAutostartID = claimDesktopAutostartID();
		if (desktopAutostartID != null) {
			String errorText = registerClient(appID, desktopAutostartID);
			if (errorText == null) return true;

			// Bugged launchers use their 'DESKTOP_AUTOSTART_ID', but forget to unset it.
			// This leaks a value that can't be used.
			// The workaround is to retry with empty ID below.
			// This pretends that parent's bug is already fixed.
			boolean parentLeakedID = errorText.startsWith("GDBus.Error:org.gnome.SessionManager.AlreadyRegistered:");	//$NON-NLS-1$
			if (!parentLeakedID) return false;
		}

		// In absence of 'DESKTOP_AUTOSTART_ID' just use empty ID.
		String errorText = registerClient(appID, "");
		if (errorText == null) return true;

		// On XFCE 'RegisterClient' is only available since 4.13.0.
		// Don't print this error since it's expected.
		if (!isGnome && errorText.startsWith("GDBus.Error:org.freedesktop.DBus.Error.UnknownMethod: "))	//$NON-NLS-1$
			return false;

		System.err.format(
				"SWT SessionManagerDBus: Failed to RegisterClient: %s%n",
				errorText);

		return false;
	}

	private boolean connectClientSignal() {
		String dbusName;
		String interfaceName;
		if (isGnome) {
			dbusName      = "org.gnome.SessionManager";					//$NON-NLS-1$
			interfaceName = "org.gnome.SessionManager.ClientPrivate";	//$NON-NLS-1$
		} else {
			dbusName      = "org.xfce.SessionManager";					//$NON-NLS-1$
			interfaceName = "org.xfce.Session.Client";					//$NON-NLS-1$
		}

		long [] error = new long [1];
		clientProxy = OS.g_dbus_proxy_new_for_bus_sync(
				OS.G_BUS_TYPE_SESSION,
				0,
				0,
				Converter.javaStringToCString(dbusName),
				Converter.javaStringToCString(clientObjectPath),
				Converter.javaStringToCString(interfaceName),
				0,
				error);

		if (clientProxy == 0) {
			System.err.format(
					"SWT SessionManagerDBus: Failed to connect to Client: %s%n",
					extractFreeGError(error[0]));
			return false;
		}

		// The rest of the code makes this key call possible
		g_signal_callback = new Callback(this, "g_signal_handler", 5);	//$NON-NLS-1$
		OS.g_signal_connect(
				clientProxy,
				Converter.javaStringToCString("g-signal"),	//$NON-NLS-1$
				g_signal_callback.getAddress(),
				0);

		return true;
	}
}
