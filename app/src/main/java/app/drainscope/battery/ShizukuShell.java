package app.drainscope.battery;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Client side of the Shizuku bridge.
 *
 * Shizuku gives the app the identity of the shell user (uid 2000) inside a
 * helper process, which is what makes per-app / per-hardware battery
 * attribution possible. Without Shizuku the app still works, but only with
 * the estimates produced by {@link UsageSampler}.
 */
public class ShizukuShell {

    public interface Listener {
        void onShizukuStateChanged(String state);
    }

    private static final int PERMISSION_REQUEST_CODE = 0x5A1;
    private static final String TAG = "DrainScope";

    private final Context context;
    private final Listener listener;
    private IBinder binder;
    private boolean binding;

    public ShizukuShell(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isServerRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isGranted() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void requestPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Binds the helper process that runs commands with shell privileges. */
    public void bind() {
        if (binding || binder != null) return;
        try {
            if (!isGranted()) {
                Log.w(TAG, "bind: not granted, skipping");
                return;
            }
            binding = true;
            Log.i(TAG, "bind: requesting user service");
            Shizuku.bindUserService(
                    new Shizuku.UserServiceArgs(new ComponentName(context, ShellService.class))
                            .daemon(true)
                            .tag("drainscope")
                            .processNameSuffix("shellservice")
                            .version(1),
                    new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            Log.i(TAG, "user service connected");
                            binder = service;
                            binding = false;
                            notifyState("connected");
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            Log.w(TAG, "user service disconnected");
                            binder = null;
                            binding = false;
                            notifyState("disconnected");
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "bind failed", t);
            binding = false;
            notifyState("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public boolean isBound() {
        return binder != null;
    }

    /** Runs a command with shell privileges. Returns null when unavailable. */
    public String exec(String cmd) {
        IBinder b = binder;
        if (b == null) {
            Log.w(TAG, "exec: no binder");
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR);
            data.writeString(cmd);
            if (!b.transact(ShellService.TRANSACTION_EXEC, data, reply, 0)) {
                Log.w(TAG, "exec: transact rejected");
                return null;
            }
            reply.readException();
            int total = reply.readInt();
            if (total <= 0) return "";
            StringBuilder sb = new StringBuilder(total);
            int offset = 0;
            while (offset < total) {
                Parcel d2 = Parcel.obtain();
                Parcel r2 = Parcel.obtain();
                try {
                    d2.writeInterfaceToken(ShellService.DESCRIPTOR);
                    d2.writeInt(offset);
                    if (!b.transact(ShellService.TRANSACTION_READ, d2, r2, 0)) break;
                    r2.readException();
                    String chunk = r2.readString();
                    int next = r2.readInt();
                    if (chunk != null) sb.append(chunk);
                    if (next <= offset) break;
                    offset = next;
                } finally {
                    d2.recycle();
                    r2.recycle();
                }
            }
            Log.i(TAG, "exec '" + cmd + "' -> " + sb.length() + " chars");
            return sb.toString();
        } catch (RemoteException e) {
            Log.e(TAG, "exec: remote exception", e);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "exec failed", t);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void unbind() {
        try {
            Shizuku.unbindUserService(
                    new Shizuku.UserServiceArgs(new ComponentName(context, ShellService.class)).tag("drainscope"),
                    null, true);
        } catch (Throwable ignored) {
        }
        binder = null;
    }

    private void notifyState(String state) {
        if (listener != null) listener.onShizukuStateChanged(state);
    }
}
