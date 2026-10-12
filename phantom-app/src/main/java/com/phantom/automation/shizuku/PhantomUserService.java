package com.phantom.automation.shizuku;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Privileged entry point. Shizuku instantiates this in a shell-UID (or root)
 * process via the {@code (Context)} constructor and uses it as the service
 * Binder; the automation engine runs here. Plain Binder protocol (no AIDL):
 *
 * <p>Protocol (all calls target the UserService Binder directly):
 * <ul>
 *   <li>1 {@code getState}: no args, reply int (0=idle, 1=running).
 *   <li>2 {@code startDaemon}: arg int port, reply int (0=ok, else failed).
 *   <li>3 {@code stopDaemon}: no args/reply payload.
 *   <li>4 {@code getError}: no args, reply string (last daemon error).
 *   <li>5 {@code exec}: args String command + int timeoutMs, reply int exit + String output.
 *   <li>16777115 Shizuku destroy: cleanup, then exit this process.
 * </ul>
 *
 * <p>NOTE: this runs in a remote process Shizuku spawns. Keep construction
 * side-effect free apart from logging: any throw here means Shizuku logs
 * server-side and the client NEVER gets a callback.
 */
public final class PhantomUserService extends Binder {
    static final String DESCRIPTOR = "com.phantom.automation.shizuku.IPhantomService";
    static final int TX_GET_STATE = 1;
    static final int TX_START_DAEMON = 2;
    static final int TX_STOP_DAEMON = 3;
    static final int TX_GET_ERROR = 4;
    static final int TX_EXEC = 5;
    /** Fixed Shizuku destroy transaction: cleanup, then exit this process. */
    static final int DESTROY_CODE = 16777115;

    private static final String TAG = "PhantomDaemon";

    public PhantomUserService() {
        Log.i(TAG, "PhantomUserService() default constructor: pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
    }

    public PhantomUserService(Context context) {
        Log.i(TAG, "PhantomUserService(Context) constructor: pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid()
                + " context=" + (context == null ? "null" : context.getClass().getName()));
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
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
        try {
            data.enforceInterface(DESCRIPTOR);
        } catch (SecurityException e) {
            Log.w(TAG, "interface token mismatch", e);
            return false;
        }
        switch (code) {
            case INTERFACE_TRANSACTION:
                reply.writeString(DESCRIPTOR);
                return true;
            case TX_GET_STATE:
                reply.writeNoException();
                reply.writeInt(PrivilegedDaemon.getState());
                return true;
            case TX_START_DAEMON: {
                int port = data.readInt();
                Log.i(TAG, "startDaemon port=" + port);
                int result = PrivilegedDaemon.start(port);
                Log.i(TAG, "startDaemon result=" + result);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }
            case TX_STOP_DAEMON:
                Log.i(TAG, "stopDaemon");
                PrivilegedDaemon.stop();
                reply.writeNoException();
                return true;
            case TX_GET_ERROR:
                reply.writeNoException();
                reply.writeString(PrivilegedDaemon.error());
                return true;
            case TX_EXEC: {
                String command = data.readString();
                int timeoutMs = data.readInt();
                int[] exit = new int[1];
                String output = PrivilegedDaemon.exec(command, timeoutMs, exit);
                reply.writeNoException();
                reply.writeInt(exit[0]);
                reply.writeString(output);
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /** Client-side helper used by the app process. */
    public static final class Client {
        private final IBinder binder;

        public Client(IBinder binder) {
            this.binder = binder;
        }

        public int getState() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                binder.transact(TX_GET_STATE, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        public int startDaemon(int port) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(port);
                binder.transact(TX_START_DAEMON, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        public void stopDaemon() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                binder.transact(TX_STOP_DAEMON, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        public String getError() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                binder.transact(TX_GET_ERROR, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        /** Runs a shell command as the UserService identity. */
        public ExecResult exec(String command, int timeoutMs) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(command == null ? "" : command);
                data.writeInt(timeoutMs);
                binder.transact(TX_EXEC, data, reply, 0);
                reply.readException();
                int exit = reply.readInt();
                String output = reply.readString();
                return new ExecResult(exit, output == null ? "" : output);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        public static final class ExecResult {
            public final int exit;
            public final String output;

            ExecResult(int exit, String output) {
                this.exit = exit;
                this.output = output;
            }
        }
    }
}
