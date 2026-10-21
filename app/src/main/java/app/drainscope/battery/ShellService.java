package app.drainscope.battery;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs as a Shizuku "user service": the Shizuku server instantiates this class
 * in a process that runs as the shell user (uid 2000), which is allowed to
 * read privileged battery statistics.
 *
 * The client talks to it with plain Binder transactions, so no AIDL tool is
 * needed to build the project. Output is transferred in chunks because a
 * full "dumpsys batterystats" dump is ~700 kB and does not fit into a single
 * binder transaction.
 */
public class ShellService extends Binder {

    public static final String DESCRIPTOR = "app.drainscope.battery.IShell";
    public static final int TRANSACTION_EXEC = 1;
    public static final int TRANSACTION_READ = 2;
    public static final int TRANSACTION_DESTROY = 16777115;
    public static final long TIMEOUT_SECONDS = 25;
    public static final int CHUNK_CHARS = 16000;

    private static String pending = "";

    public ShellService() {
        Log.i("DrainScope", "ShellService created in uid " + android.os.Process.myUid());
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TRANSACTION_EXEC) {
            data.enforceInterface(DESCRIPTOR);
            String cmd = data.readString();
            String out = exec(cmd);
            synchronized (ShellService.class) {
                pending = out;
            }
            reply.writeNoException();
            reply.writeInt(out.length());
            return true;
        } else if (code == TRANSACTION_READ) {
            data.enforceInterface(DESCRIPTOR);
            int offset = data.readInt();
            String buf;
            synchronized (ShellService.class) {
                buf = pending;
            }
            int end = Math.min(offset + CHUNK_CHARS, buf.length());
            String chunk = offset >= buf.length() ? "" : buf.substring(offset, end);
            reply.writeNoException();
            reply.writeString(chunk);
            reply.writeInt(end);
            return true;
        } else if (code == TRANSACTION_DESTROY) {
            reply.writeNoException();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    /** Executes a shell command and returns stdout+stderr. Never throws. */
    public static String exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return "";
        Process p = null;
        final StringBuilder sb = new StringBuilder();
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = br.readLine()) != null) {
                            synchronized (sb) {
                                sb.append(line).append('\n');
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroy();
                synchronized (sb) {
                    sb.append("\n[timeout after ").append(TIMEOUT_SECONDS).append("s]\n");
                }
            }
            reader.join(1000);
            Log.i("DrainScope", "shell exec '" + cmd + "' uid=" + android.os.Process.myUid()
                    + " -> " + sb.length() + " chars");
            return sb.toString();
        } catch (Exception e) {
            Log.e("DrainScope", "shell exec failed", e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
