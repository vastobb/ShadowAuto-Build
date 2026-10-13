package com.phantom.automation.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;

/** Binds the privileged UserService from the app process. Fully logged. */
public final class ServiceConnector {
    private static final String TAG = "PhantomBind";

    public interface Listener {
        void onConnected(PhantomUserService.Client client);

        void onDisconnected();

        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private ServiceConnection connection;
    private Shizuku.UserServiceArgs boundArgs;

    public ServiceConnector(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Returns true when a fresh bind was issued, false when reusing. */
    public boolean bind() {
        if (connection != null) {
            Log.i(TAG, "bind: reusing existing binding (no fresh bind issued)");
            return false;
        }
        ComponentName component = new ComponentName(context.getPackageName(),
                PhantomUserService.class.getName());
        Log.i(TAG, "bind: component=" + component.flattenToString()
                + " version=" + com.phantom.automation.BuildConfig.VERSION_CODE
                + " binderAlive=" + Shizuku.pingBinder()
                + " permission=" + Shizuku.checkSelfPermission());
        try {
            Log.i(TAG, "bind: shizukuVersion=" + Shizuku.getVersion()
                    + " uid=" + Shizuku.getUid());
        } catch (Throwable e) {
            Log.w(TAG, "bind: version/uid query failed", e);
        }
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(component)
                    .daemon(false)
                    .processNameSuffix("phantom_priv")
                    .version(com.phantom.automation.BuildConfig.VERSION_CODE);
            boundArgs = args;
            Log.i(TAG, "bind: calling Shizuku.bindUserService…");
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    Log.i(TAG, "onServiceConnected: " + name.flattenToString()
                            + " binder=" + (binder == null ? "null" : binder.getClass().getName()));
                    if (listener != null) {
                        if (binder == null) {
                            listener.onError("Shizuku returned a null service binder.");
                        } else {
                            listener.onConnected(new PhantomUserService.Client(binder));
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.i(TAG, "onServiceDisconnected: " + name.flattenToString());
                    if (listener != null) {
                        listener.onDisconnected();
                    }
                }
            };
            Shizuku.bindUserService(args, connection);
            Log.i(TAG, "bind: Shizuku.bindUserService returned without exception; "
                    + "waiting for onServiceConnected");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "bind: Shizuku.bindUserService threw", e);
            connection = null;
            boundArgs = null;
            if (listener != null) {
                listener.onError("bind failed: " + e
                        + ". Open the Shizuku app and verify it is running.");
            }
            return false;
        }
    }

    public void unbind() {
        if (connection == null) {
            return;
        }
        Log.i(TAG, "unbind");
        try {
            Shizuku.unbindUserService(boundArgs, connection, false);
        } catch (Throwable e) {
            Log.w(TAG, "unbind threw", e);
        }
        connection = null;
        boundArgs = null;
    }

    public static String describeError(RemoteException e) {
        return e.getMessage() == null ? "binder call failed" : e.getMessage();
    }
}
