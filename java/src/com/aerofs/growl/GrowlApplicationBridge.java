/*
 * Copyright (c) 2011, Air Computing Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer. Redistributions in binary
 * form must reproduce the above copyright notice, this list of conditions and
 * the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.aerofs.growl;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;

public class GrowlApplicationBridge
{
    private static final NativeMethods _growl;

    private interface NativeMethods
    {
        boolean isMistEnabled();
        boolean isGrowlRunning();
        void register(GrowlApplicationBridge bridge, String appName, byte[] iconData, String[] allNotifications, String[] enabledNotifications);
        void release();
        void notify(String title, String message, String notificationName, byte[] iconData, int priority, boolean isSticky, int clickContext, String groupId);
        UnsatisfiedLinkError getLinkerException();
    }

    private static class GrowlNativeMethods implements NativeMethods
    {
        public native boolean isMistEnabled();
        public native boolean isGrowlRunning();
        public native void register(GrowlApplicationBridge bridge, String appName, byte[] iconData, String[] allNotifications, String[] enabledNotifications);
        public native void release();
        public native void notify(String title, String message, String notificationName, byte[] iconData, int priority, boolean isSticky, int clickContext, String groupId);
        public UnsatisfiedLinkError getLinkerException() { return null; }
    }

    private static class DummyNativeMethods implements NativeMethods
    {
        private UnsatisfiedLinkError _e;
        public DummyNativeMethods(UnsatisfiedLinkError e) { _e = e; }
        public boolean isMistEnabled() { return false; }
        public boolean isGrowlRunning() { return false; }
        public void register(GrowlApplicationBridge bridge, String appName, byte[] iconData, String[] allNotifications, String[] enabledNotifications) {}
        public void release() {}
        public void notify(String title, String message, String notificationName, byte[] iconData, int priority, boolean isSticky, int clickContext, String groupId) {}
        public UnsatisfiedLinkError getLinkerException() { return _e; }
    }

    // Callbacks (called by native code)
    void onNotificationClicked(int clickContext)
    {
        Notification n = _pendingCallbacks.remove(clickContext);
        if (n != null && n.getClickedCallback() != null) {
            n.getClickedCallback().run();
        }
    }

    void onNotificationDismissed(int clickContext)
    {
        Notification n = _pendingCallbacks.remove(clickContext);
        if (n != null && n.getDismissedCallback() != null) {
            n.getDismissedCallback().run();
        }
    }

    static {
        NativeMethods methods;
        try {
            System.loadLibrary("GrowlJavaBridge");
            methods = new GrowlNativeMethods();
        } catch (UnsatisfiedLinkError e) {
            System.out.println("Failed to load the Growl library (libGrowlJavaBridge.jnilib): " + e);
            methods = new DummyNativeMethods(e); // save the exception to throw it later on registerNotifications()
        }
        _growl = methods;
    }

    String _appName;
    File _defaultIcon;
    private HashMap<Integer, Notification> _pendingCallbacks = new HashMap<Integer, Notification>();
    private long _timeLastNotif;

    /**
     * Creates a new bridge to talk to Growl
     * @param appName the human-readable name of your application. Should not change across versions.
     */
    public GrowlApplicationBridge(String appName)
    {
        _appName = appName;
    }

    /**
     * Sets the default icon that will be displayed with notifications that don't have any specific icon.
     * If you don't set any icon, Growl will try to use the icon from your application bundle.
     * @param icon png file of the icon to display
     */
    public void setDefaultIcon(File icon)
    {
        _defaultIcon = icon;
    }

    /**
     * @return true if the JNI library and the Growl framework were correctly loaded.
     * If isFrameworkLoaded() is false, calling notify() won't have any effect, and
     * registerNotifications() will throw an exception indicating the exact cause of the error.
     */
    public boolean isFrameworkLoaded()
    {
        return (_growl.getLinkerException() == null);
    }

    /**
     * @return true if Growl is ready and running.
     */
    public boolean isGrowlRunning()
    {
        return _growl.isGrowlRunning();
    }

    /**
     * @return true if Growl is not running and the notifications will be displayed with
     * Growl's built-in notification system (Mist).
     */
    public boolean willUseBuiltinNotifications()
    {
        return _growl.isMistEnabled();
    }

    /**
     * @return Time elapsed since the last notification, in milliseconds
     */
    public long timeSinceLastNotification()
    {
        return System.currentTimeMillis() - _timeLastNotif;
    }

    /**
     * Register your notifications with Growl
     * You must call this method once, otherwise the notifications won't be displayed
     * @param types all the notification types you intend to display
     * @throws UnsatisfiedLinkError if the JNI library failed to load.
     * In this case, nothing will happen if you call notify().
     */
    public void registerNotifications(NotificationType... types) throws UnsatisfiedLinkError
    {
        if (!this.isFrameworkLoaded()) {
            throw _growl.getLinkerException();
        }

        ArrayList<String> allNotifications = new ArrayList<String>();
        ArrayList<String> enabledNotifications = new ArrayList<String>();

        for(NotificationType nt : types) {
            allNotifications.add(nt.getName());
            if (!nt.isDisabledByDefault()) {
                enabledNotifications.add(nt.getName());
            }
        }

        String[] allNotifArr = allNotifications.toArray(new String[allNotifications.size()]);
        String[] enabledNotifArr = enabledNotifications.toArray(new String[enabledNotifications.size()]);

        _growl.register(this, _appName, readFile(_defaultIcon), allNotifArr, enabledNotifArr);
    }

    /**
     * Tries to display the notification using either Growl or Growl's built-in notification system
     * @param n
     */
    public void notify(Notification n)
    {
        int clickContext = 0;
        if (n.getClickedCallback() != null || n.getDismissedCallback() != null) {
            clickContext = n.hashCode();
            _pendingCallbacks.put(clickContext, n);
        }

        // Due to bugs in Growl 1.3, it is possible that we miss one or more onDisposed or onClicked calls
        // Because of that, we could leak memory because notifications in _pendingCallbacks would never get deleted
        // So if we have more than 16 notifications pending and it has been 30 seconds since the last notification
        // we assume that all notifications are long gone and we clear _pendingCallbacks
        // Obviously, this might be bad if we used a lot of sticky notifications - which is not the case.
        if (_pendingCallbacks.size() > 16 && timeSinceLastNotification() > 30 * 1000) {
            _pendingCallbacks.clear();
        }

        NotificationType type = n.getType();
        File icon = (n.getIcon() != null) ? n.getIcon() : type.getDefaultIcon();
        _growl.notify(n.getTitle(), n.getMessage(), type.getName(), readFile(icon), n.getPriority().getValue(), n.isSticky(), clickContext, n.getGroup());
        _timeLastNotif = System.currentTimeMillis();
    }

    /**
     * Frees the memory associated with Growl.
     * If Growl isn't loaded, it is a no-op.
     */
    public void release()
    {
        _growl.release();
    }

    // Helper methods

    /**
     * Simple method to read a file to a byte array
     * There is a race condition if the file length changes while the file is being read, but that should be ok
     */
    private byte[] readFile(File file)
    {
        if (file == null) {
            return null;
        }

        try {
            RandomAccessFile f = new RandomAccessFile(file, "r");
            byte[] data = new byte[(int) f.length()];
            f.readFully(data);
            return data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
