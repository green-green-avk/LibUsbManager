package green_green_avk.libusbmanager;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Map;

/**
 * A <b>libusb</b> manager for Termux-like applications.
 * Device enumeration and hot plug/unplug events are supported.
 * To be used with
 * <a href="https://github.com/green-green-avk/libusb-1.0.23-android-helper-service-patch">libusb-1.0.23-android-helper-service-patch</a>
 */
@SuppressWarnings("WeakerAccess,unused")
public class LibUsbManager {
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private static final String ACTION_USB_ATTACHED =
            "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    private static final String ACTION_USB_DETACHED =
            "android.hardware.usb.action.USB_DEVICE_DETACHED";

    private static final class ParseException extends RuntimeException {
        private ParseException(final String message) {
            super(message);
        }
    }

    private static final class ProcessException extends RuntimeException {
        private ProcessException(final String message) {
            super(message);
        }
    }

    @NonNull
    protected final Context ctx;
    @NonNull
    private final String sockName;
    @NonNull
    private final Thread lth;

    /**
     * Something just to be reported happened.
     */
    protected void onClientException(@NonNull final Throwable e) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, ctx.getString(R.string.libusbmanager_msg_error_s,
                        e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Something sinister happened...
     */
    protected void onClientError(@NonNull final Throwable e) {
        rethrow(e);
    }

    /**
     * Something sinister happened...
     */
    protected void onServerError(@NonNull final Throwable e) {
        rethrow(e);
    }

    /**
     * Called after final cleanup.
     */
    protected void onServerExit() {
    }

    private void rethrow(@NonNull final Throwable e) {
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        if (e instanceof Error) throw (Error) e;
        throw new RuntimeException(e);
    }

    @NonNull
    private UsbManager getUsbManager() {
        final UsbManager usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (usbManager == null)
            throw new ProcessException(ctx.getString(
                    R.string.libusbmanager_msg_cannot_obtain_usb_service));
        return usbManager;
    }

    private final Object obtainUsbPermissionLock = new Object();

    private void obtainUsbPermission(@NonNull final UsbDevice dev) {
        synchronized (obtainUsbPermissionLock) {
            final Object lock = new Object();
            final UsbManager usbManager = getUsbManager();
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    final String action = intent.getAction();
                    if (!ACTION_USB_PERMISSION.equals(action)) return;
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            };
            synchronized (lock) {
                ctx.registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));
                usbManager.requestPermission(dev, PendingIntent.getBroadcast(
                        ctx, 0, new Intent(ACTION_USB_PERMISSION), 0));
                try {
                    lock.wait();
                } catch (final InterruptedException ignored) {
                } finally {
                    ctx.unregisterReceiver(receiver);
                }
            }
        }
    }

    private static final int DEV_ATTACHED = 0;
    private static final int DEV_DETACHED = 1;
    private static final int DEV_EXISTS = 2;

    private void tryWriteDeviceName(@NonNull final LocalSocket socket,
                                    @Nullable final UsbDevice dev, final int state) {
        try {
            final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (state != DEV_EXISTS) out.writeByte(state);
            out.writeUTF(dev == null ? "" : dev.getDeviceName());
        } catch (final Throwable e) {
            onClientException(e);
        }
    }

    private void devListClient(@NonNull final LocalSocket socket) throws IOException {
        final HandlerThread outTh = new HandlerThread("libUsbEventsOutput");
        outTh.start();
        final Handler outH = new Handler(outTh.getLooper());
        outH.post(new Runnable() {
            @Override
            public void run() {
                for (final UsbDevice dev : getUsbManager().getDeviceList().values())
                    tryWriteDeviceName(socket, dev, DEV_EXISTS);
                tryWriteDeviceName(socket, null, DEV_EXISTS);
            }
        });
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, @NonNull final Intent intent) {
                final String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case ACTION_USB_ATTACHED:
                        tryWriteDeviceName(socket,
                                (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE),
                                DEV_ATTACHED);
                        break;
                    case ACTION_USB_DETACHED:
                        tryWriteDeviceName(socket,
                                (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE),
                                DEV_DETACHED);
                        break;
                }
            }
        };
        final IntentFilter iflt = new IntentFilter(ACTION_USB_ATTACHED);
        iflt.addAction(ACTION_USB_DETACHED);
        ctx.registerReceiver(receiver, iflt, null, outH);
        try {
            while (socket.getInputStream().read() != -1) ; // Wait for closing by the client...
        } finally {
            ctx.unregisterReceiver(receiver);
            outTh.quit();
        }
    }

    private void client(@NonNull final LocalSocket socket) {
        UsbDeviceConnection devConn = null;
        try {
            // TODO: Check conditions
            if (Process.myUid() != socket.getPeerCredentials().getUid())
                throw new ParseException(ctx.getString(
                        R.string.libusbmanager_msg_spoofing_detected));
            // =======
            final InputStream cis = socket.getInputStream();
            final DataInputStream dis = new DataInputStream(cis);
            final String devName = dis.readUTF();
            if (devName.length() <= 0) {
                devListClient(socket);
                return;
            }
            final UsbManager usbManager = getUsbManager();
            final Map<String, UsbDevice> devList = usbManager.getDeviceList();
            final UsbDevice dev = devList.get(devName);
            if (dev == null)
                throw new ProcessException(ctx.getString(
                        R.string.libusbmanager_msg_no_device_found_s, devName));
            obtainUsbPermission(dev);
            devConn = usbManager.openDevice(dev);
            if (devConn == null)
                throw new ProcessException(ctx.getString(
                        R.string.libusbmanager_msg_unable_to_open_device_s, devName));
            socket.setFileDescriptorsForSend(new FileDescriptor[]{
                    ParcelFileDescriptor.adoptFd(devConn.getFileDescriptor()).getFileDescriptor()
            });
            socket.getOutputStream().write(0);
            while (cis.read() != -1) ; // Wait for closing by the client...
        } catch (final InterruptedIOException ignored) {
        } catch (final SecurityException | IOException |
                ParseException | ProcessException e) {
            onClientException(e);
        } finally {
            if (devConn != null) devConn.close();
        }
    }

    private final Runnable server = new Runnable() {
        @Override
        public void run() {
            LocalServerSocket serverSocket = null;
            try {
                serverSocket = new LocalServerSocket(sockName);
                while (!Thread.interrupted()) {
                    final LocalSocket socket = serverSocket.accept();
                    final Thread cth = new Thread() {
                        @Override
                        public void run() {
                            try {
                                client(socket);
                            } catch (final Throwable e) {
                                onClientError(e);
                            } finally {
                                try {
                                    socket.close();
                                } catch (final IOException ignored) {
                                } catch (final Throwable e) {
                                    onClientError(e);
                                }
                            }
                        }
                    };
                    cth.setDaemon(false);
                    cth.start();
                }
            } catch (final InterruptedIOException ignored) {
            } catch (final Throwable e) {
                onServerError(e);
            } finally {
                if (serverSocket != null)
                    try {
                        serverSocket.close();
                    } catch (final Throwable ignored) {
                    }
                onServerExit();
            }
        }
    };

    /**
     * Like {@link #LibUsbManager(Context ctx, String sockName)} but with
     * <code>sockName = ctx.getApplicationContext().getPackageName() + ".libusb"</code>.
     *
     * @param ctx Application context.
     */
    public LibUsbManager(@NonNull final Context ctx) {
        this(ctx, ctx.getApplicationContext().getPackageName() + ".libusb");
    }

    /**
     * Creates and starts a <b>libusb</b> manager instance for your application.
     * (Yes, this simple)
     * <p>Usage:</p>
     * <pre><code>
     * ...
     * {@literal @}Keep // Protect from unwanted destruction in a case minifier is used.
     * {@literal @}NonNull
     * private usbMan;
     * ...
     * usbMan = new LibUsbManager(ctx);
     * ...
     * </code></pre>
     *
     * @param ctx      Application context.
     * @param sockName Socket name.
     */
    public LibUsbManager(@NonNull final Context ctx, @NonNull final String sockName) {
        this.ctx = ctx.getApplicationContext();
        this.sockName = sockName;
        lth = new Thread(server, "LibUsbServer");
        lth.setDaemon(true);
        lth.start();
    }

    @Override
    protected void finalize() throws Throwable {
        lth.interrupt();
        super.finalize();
    }
}
