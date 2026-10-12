package com.phantom.automation.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

/** Binds the privileged UserService from the app process. */
public final class ServiceConnector {
    public interface Listener {
        void onConnected(PhantomUserService.Client client);

        void onDisconnected();

        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private ServiceConnection connection;

    public ServiceConnector(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void bind() {
        if (connection != null) {
            return;
        }
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(),
                        PhantomUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("phantom_priv")
                .versionCode(1);
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (listener != null) {
                    listener.onConnected(new PhantomUserService.Client(binder));
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (listener != null) {
                    listener.onDisconnected();
                }
            }
        };
        try {
            Shizuku.bindUserService(args, connection);
        } catch (Throwable e) {
            connection = null;
            if (listener != null) {
                listener.onError("bind failed: " + e.getMessage());
            }
        }
    }

    public void unbind() {
        if (connection == null) {
            return;
        }
        try {
            Shizuku.unbindUserService(connection);
        } catch (Throwable ignored) {
        }
        connection = null;
    }

    public static String describeError(RemoteException e) {
        return e.getMessage() == null ? "binder call failed" : e.getMessage();
    }
}
