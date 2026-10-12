package com.phantom.automation.shizuku;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/** Shizuku availability detection and permission handling. No root assumed. */
public final class ShizukuManager {
    public interface Listener {
        void onStateChanged(State state);
    }

    public enum State {
        /** Shizuku (or Sui) app is not installed or binder is absent. */
        UNAVAILABLE,
        /** Binder alive, permission not yet granted. */
        NEEDS_PERMISSION,
        /** Permission previously denied with "don't ask again". */
        DENIED_PERMANENTLY,
        /** Binder alive and permission granted. Privileged ops may proceed. */
        READY,
        /** Shizuku pre-v11: unsupported. */
        UNSUPPORTED_VERSION,
    }

    public static final int REQUEST_CODE = 1001;

    private final Listener listener;
    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> emit(currentState());
    private final Shizuku.OnBinderDeadListener binderDead =
            () -> emit(State.UNAVAILABLE);
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_CODE) {
                    emit(currentState());
                }
            };
    private boolean attached;

    public ShizukuManager(Listener listener) {
        this.listener = listener;
    }

    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        emit(currentState());
    }

    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
    }

    /** Re-evaluate state and notify the listener. Manual reconnect action. */
    public void refresh() {
        emit(currentState());
    }

    /** Returns true when the permission request was actually sent. */
    public boolean requestPermission() {
        if (!Shizuku.pingBinder()) {
            emit(State.UNAVAILABLE);
            return false;
        }
        if (Shizuku.isPreV11()) {
            emit(State.UNSUPPORTED_VERSION);
            return false;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            emit(State.READY);
            return false;
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            emit(State.DENIED_PERMANENTLY);
            return false;
        }
        Shizuku.requestPermission(REQUEST_CODE);
        return true;
    }

    public static boolean isInstalledHint(android.content.Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static State currentState() {
        if (!Shizuku.pingBinder()) {
            return State.UNAVAILABLE;
        }
        if (Shizuku.isPreV11()) {
            return State.UNSUPPORTED_VERSION;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return State.READY;
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return State.DENIED_PERMANENTLY;
        }
        return State.NEEDS_PERMISSION;
    }

    private void emit(State state) {
        if (listener != null) {
            listener.onStateChanged(state);
        }
    }
}
