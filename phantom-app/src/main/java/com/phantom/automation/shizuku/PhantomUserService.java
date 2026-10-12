package com.phantom.automation.shizuku;

import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Privileged entry point. Shizuku starts this in a shell-UID (or root)
 * process; the automation engine runs here, exactly where the legacy
 * {@code app_process} shell ran. The UI talks to it through AIDL, and all
 * automation traffic flows over the loopback RPC daemon (no LAN).
 */
public final class PhantomUserService extends IPhantomService.Stub {
    private static final String TAG = "PhantomUserService";
    /** Fixed Shizuku destroy transaction: cleanup, then exit this process. */
    private static final int DESTROY_CODE = 16777115;

    public PhantomUserService() {
        Log.i(TAG, "created");
    }

    public PhantomUserService(Context context) {
        Log.i(TAG, "created with context");
    }

    @Override
    public int getState() {
        return PrivilegedDaemon.getState();
    }

    @Override
    public int startDaemon(int port) {
        return PrivilegedDaemon.start(port);
    }

    @Override
    public void stopDaemon() {
        PrivilegedDaemon.stop();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == DESTROY_CODE) {
            Log.i(TAG, "destroy requested");
            PrivilegedDaemon.stop();
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }).start();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
