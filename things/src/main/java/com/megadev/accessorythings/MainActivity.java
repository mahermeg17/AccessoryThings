package com.megadev.accessorythings;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends Activity implements Runnable {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.examples.accessory.controller.action.USB_PERMISSION";

    private UsbManager mUsbManager;
    private PendingIntent mPermissionIntent;
    private boolean mPermissionRequestPending;

    UsbAccessory mAccessory;
    ParcelFileDescriptor mFileDescriptor;
    FileInputStream mInputStream;
    FileOutputStream mOutputStream;

    private static final int MESSAGE_SWITCH = 1;
    private static final int MESSAGE_JOY = 4;
    private static final int MESSAGE_VIBE = 5;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);

        setContentView(R.layout.main);

    }

    @Override
    public void onResume() {
        super.onResume();

        Intent intent = getIntent();
        if (mInputStream != null && mOutputStream != null) {
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();

        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {
            Log.i(TAG, "accessories " + accessories.length);
            if (mUsbManager.hasPermission(accessory)) {
                openAccessory(accessory);
            } else {
                synchronized (mUsbReceiver) {
                    if (!mPermissionRequestPending) {
                        mUsbManager.requestPermission(accessory,
                                mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        } else {
            Log.i(TAG, "mAccessory is null");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        closeAccessory();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }


    private void openAccessory(UsbAccessory accessory) {
        mFileDescriptor = mUsbManager.openAccessory(accessory);
        if (mFileDescriptor != null) {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);
            Thread thread = new Thread(null, this, "AccessoryController");
            thread.start();
            Log.i(TAG, "accessory opened");
        } else {
            Log.i(TAG, "accessory open fail");
        }
    }

    private void closeAccessory() {

        try {
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
            }
        } catch (IOException e) {
        } finally {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    /*
     * This receiver monitors for the event of a user granting permission to use
     * the attached accessory.  If the user has checked to always allow, this will
     * be generated following attachment without further user interaction.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openAccessory(accessory);
                    } else {
                        Log.i(TAG, "permission denied for accessory " + accessory);
                    }
                    mPermissionRequestPending = false;
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && accessory.equals(mAccessory)) {
                    closeAccessory();
                }
            }
        }
    };

    /*
     * Runnable block that will poll the accessory data stream
     * for regular updates, posting each message it finds to a
     * Handler.  This is run on a spawned background thread.
     */
    public void run() {
        int ret = 0;
        byte[] buffer = new byte[16384];
        int i;

        while (ret >= 0) {
            try {
                ret = mInputStream.read(buffer);
            } catch (IOException e) {
                break;
            }

            i = 0;
            while (i < ret) {
                int len = ret - i;

                switch (buffer[i]) {
                    case 0x1:
                        if (len >= 3) {
                            Message m = Message.obtain(mHandler, MESSAGE_SWITCH);

                            mHandler.sendMessage(m);
                        }
                        i += 3;
                        break;

                    case 0x6:
                        if (len >= 3) {
                            Message m = Message.obtain(mHandler, MESSAGE_JOY);

                            mHandler.sendMessage(m);
                        }
                        i += 3;
                        break;

                    default:
                        Log.i(TAG, "unknown msg: " + buffer[i]);
                        i = len;
                        break;
                }
            }

        }
    }

    /*
     * This Handler receives messages from the polling thread and
     * injects them into the GameActivity methods on the main thread.
     */
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SWITCH:
                    Log.i(TAG, "MESSAGE_SWITCH");
                    break;

                case MESSAGE_JOY:
                    Log.i(TAG, "MESSAGE_JOY");
                    break;

                case MESSAGE_VIBE:
                    try {
                        Log.i(TAG, "MESSAGE_VIBE");
                        byte[] v = (byte[]) msg.obj;
                        mOutputStream.write(v);
                        mOutputStream.flush();
                    } catch (IOException e) {
                        Log.w("AccessoryController", "Error writing vibe output");
                    }
                    break;
            }
        }
    };

}
