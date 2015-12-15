/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.shrlnm.android.erm;

//import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
//import android.view.SurfaceHolder;
//import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
//import android.widget.EditText;
//import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.shrlnm.android.erm.ElmRenaultMonitor_ElmThread.STATE_CONNECTED;
import static com.shrlnm.android.erm.ElmRenaultMonitor_ElmThread.STATE_CONNECTING;
import static com.shrlnm.android.erm.ElmRenaultMonitor_ElmThread.STATE_LISTEN;
import static com.shrlnm.android.erm.ElmRenaultMonitor_ElmThread.STATE_NONE;

//import com.shrlnm.android.erm.R;

//import android.os.Environment;

/**
 * This is the main Activity that displays main window.
 */
public class ElmRenaultMonitor_Main extends Activity {
    // Debugging
    private static final String TAG = "ERM_main_debug";
    private static final boolean D = true;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE    = 1;
    public static final int MESSAGE_READ            = 2;
    public static final int MESSAGE_WRITE           = 3;
    public static final int MESSAGE_DEVICE_NAME     = 4;
    public static final int MESSAGE_TOAST           = 5;

    // Message types sent from other activities
    public static final int MESSAGE_ACT_STOP_REPEAT         = 1;
    public static final int MESSAGE_ACT_SND_CMD             = 2;
    public static final int MESSAGE_ACT_SEND_ME_NEW_DATA    = 3;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST       = "toast";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT      = 3;

    // Layout Views
    //private TextView m_par_RPM;
    //private EditText mConsoleOut;
    //private EditText mOutEditText;

    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private ElmRenaultMonitor_ElmThread mChatService = null;

    // Additional constants
    private static boolean   waitcmd = false;

    // Last sent command
    private String          lastCommand = null;
                            // Command queue, to send new command just add it there
    private List<String>    cmd_queue = new ArrayList<String>();
                            // to repeat command continuously put it there and set reapeatCmd
    private String          repetitiveCmd = "";
    private boolean         repeatCmd = false;

    SharedPreferences sharedPref;

    private PowerManager            powerManager;
    private PowerManager.WakeLock   wakeLock;

    private InstrumentsView         iv;

    private ecu                     eRsp;

    private int errorPollingPeriod = 60000;

    // Starts first. Enable bluetooth, load previous MAC and register receiver
    @Override
    public void onCreate(Bundle savedInstanceState) {

        //Enter full screen mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        super.onCreate(savedInstanceState);
        if(D) Log.e(TAG, "+++ ON CREATE +++");

        powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "MyLock");

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(localMessageReceiver,
                new IntentFilter("ElmRenaultMonitor_Main"));

        // there we will save the mac address of BT device for not to ask it again
        sharedPref = this.getSharedPreferences(getString(R.string.preference_addr_key), MODE_PRIVATE);

        // Set up the window layout
        //setContentView(R.layout.main);
        iv = new InstrumentsView(this);
        setContentView(iv);

        eRsp = new ecu();

    }

    // Starts second. Start and connect bluetooth
    @Override
    public void onStart() {
        super.onStart();
        if(D) Log.e(TAG, "++ ON START ++");

        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        // Otherwise, setup the chat session
        } else {
            if (mChatService == null) setupChat();
        }

        if (STATE_CONNECTED != mChatService.getState()) {
            // load saved mac address.
            String mac_address = sharedPref.getString(getString(R.string.preference_addr_key), "");
            if ((mac_address != null ? mac_address.length() : 0) == 17) {
                // it is and we just connect to it
                connectDevice(mac_address);
            } else {
                // there is no saved address then ask it
                try {
                    Intent serverIntent = new Intent(this, DeviceListActivity.class);
                    startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                } catch (android.content.ActivityNotFoundException e ) {
                    if(D) Log.e(TAG, "+++ ActivityNotFoundException +++");
                }

            }
        }
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if(D) Log.e(TAG, "+ ON RESUME +");

        // Acquire wakeLock
        wakeLock.acquire();

        // resume rendering thread
        //iv.resume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (STATE_NONE == mChatService.getState()) {
              // Start the Bluetooth chat services
              mChatService.start();
            }
        }
    }

    // Handler for received Intents. This will be called whenever an Intent
    // with an action named "msg" is broadcasted.
    private BroadcastReceiver localMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int msg = intent.getIntExtra("msg", MESSAGE_ACT_STOP_REPEAT);
            if (D) Log.d(TAG, "Got message: " + msg);

            switch (msg) {
                case MESSAGE_ACT_STOP_REPEAT:
                    repetitiveCmd = "";
                    repeatCmd = false;
                    break;
                case MESSAGE_ACT_SND_CMD:
                    repetitiveCmd = intent.getStringExtra("cmd");
                    repeatCmd = true;
                    sendCmd(""); //just for check the queue
                    break;
                case MESSAGE_ACT_SEND_ME_NEW_DATA:
                    sendCmd("");
                    break;
            }
        }
    };

    private void setupChat() {
        Log.d(TAG, "setupChat()");

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new ElmRenaultMonitor_ElmThread(mHandler);
    }

    @Override
    public synchronized void onPause() {
        super.onPause();
        if(D) Log.e(TAG, "- ON PAUSE -");

        //release wakeLock
        wakeLock.release();

        //pause rendering thread
        //iv.pause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if(D) Log.e(TAG, "-- ON STOP --");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null) mChatService.stop();
        if(D) Log.e(TAG, "--- ON DESTROY ---");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localMessageReceiver);
    }

    private void setStatus(int resId) {
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(resId);
        }
    }

    private void setStatus(CharSequence subTitle) {
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(subTitle);
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    connectDevice(address);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    private void connectDevice(String address) {
        // address is the device MAC address
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device);

        // save mac address for not to ask it again
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(getString(R.string.preference_addr_key), address);
        editor.apply();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent serverIntent;
        switch (item.getItemId()) {
            case R.id.bt_connect_scan:
                // Launch the DeviceListActivity to see devices and do scan
                serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                return true;
            case R.id.init_elm:
                // initialize ELM
                cmd_queue.clear();
                repetitiveCmd = "";
                repeatCmd = false;
                waitcmd = false;
                initELM();
                return true;
            case R.id.exit_from_program:
                finish();
        }
        return false;
    }

    // The Handler for the periodic error poling
    Handler hErrorsPoling = new Handler();
    private Runnable errorPoller = new Runnable() {
        @Override
        public void run() {
            sendCmd("at caf1");     // disable frame auto formatting
            sendCmd("10C0");        // open communication session
            sendCmd("17FF00");      // read errors STD-A
            sendCmd("1902AF");      // read errors STD-B
            sendCmd("at caf0");     // disable frame auto formatting
            sendCmd("at ma");
            hErrorsPoling.postDelayed(errorPoller, errorPollingPeriod);
        }
    };

    // The Handler that gets information back from the BluetoothChatService
    final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_STATE_CHANGE:
                if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case STATE_CONNECTED:
                    setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                    initELM();
                    break;
                case STATE_CONNECTING:
                    setStatus(R.string.title_connecting);
                    break;
                case STATE_LISTEN:
                case STATE_NONE:
                    setStatus(R.string.title_not_connected);
                    break;
                }
                break;
            case MESSAGE_WRITE:
                byte[] writeBuf = (byte[]) msg.obj;
                // construct a string from the buffer
                String writeMessage = new String(writeBuf);
                if (writeMessage.length()>2) {
                    lastCommand = writeMessage.replace("\r", "");
                }
                break;
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readenMessage = new String(readBuf, 0, msg.arg1);
                if(D) Log.i(TAG, "handler resp: " + msg.arg1 + " : " + readenMessage );

                for ( int i=0; i<readenMessage.length(); i++ ) {
                    if (readenMessage.charAt(i)=='>') waitcmd = false;
                }

                //if (readenMessage.length()==1 ) break; // to short (probably \n)


                //if (!repeatCmd) {
                //    //mConsoleOut.append(readenMessage);
                //    waitcmd = false;
                //}

                if (readenMessage.charAt(0)=='B' && readenMessage.charAt(1)=='U') { //BUFFER FULL
                    sendCmd("at ma");
                    break;
                }

                //readenMessage = normalizeResponse( readenMessage );
                parseResult( lastCommand, readenMessage );
                sendCmd(""); //check if cmd_queue is not empty
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                Toast.makeText(getApplicationContext(), "Connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
                break;
            }
        }
    };

    private void sendCmd(String cmd) {

        if(D) Log.d(TAG, "sendCmd cmd:" + cmd + " waitcmd:" + waitcmd + " cmd_queue.len:" + cmd_queue.size());

        // Check that we're actually connected before trying anything
        if (mChatService.getState() != STATE_CONNECTED) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        if (cmd.length()>0) cmd_queue.add(cmd);

        if (waitcmd) return;   // we are waiting results from previous command

        if (cmd_queue.size()==0 && repetitiveCmd.length()==0) return;   // nothing to send

        if (cmd_queue.size()>0) {
            // cmd_queue not empty - it has priority
            cmd = cmd_queue.get(0);
            cmd_queue.remove(0);
        } else {
            if (repeatCmd && repetitiveCmd.equals(lastCommand)) { cmd = "\r"; }
            else {
                if (repetitiveCmd.length()>0) {
                    cmd = repetitiveCmd;
                    repeatCmd = true;
                }
            }
        }

        waitcmd = true;
        // Add \r
        if (!cmd.equals("\r")) cmd = cmd+"\r";
        byte[] send = cmd.getBytes();
        mChatService.write(send);

    }

    private static boolean is0x(char c) {
        return ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'));
    }

    private void parseResult( String cmd, String rsp ) {
        if(D) Log.d(TAG, "parseResult cmd:" + cmd + " resp:" + rsp );

        if (rsp.length()<8) return;
        if (!( is0x(rsp.charAt(0)) && is0x(rsp.charAt(1)) && is0x(rsp.charAt(2))))
            return;

        try {
            //extract address
            Integer address = Integer.parseInt(rsp.substring(0, 3), 16);

            // check if elm is appropriate initialized
            if ( rsp.charAt(3)==' ' && is0x(rsp.charAt(4)) && rsp.charAt(5)!=' ' ) {
                initELM( );
                return;
            }

            //extract frame length
            Integer length = Integer.parseInt(rsp.substring(4, 5), 16);

            //extract frame data
            String df = rsp.substring(6, rsp.length());
            df = df.replace(" ", "");

            if ( address!=0x7E8 && df.length() < length * 2)
                return;

            Integer tmp;
            String  errRsp;

            switch (address) {
                case 0x7E8:
                    eRsp.nextFrame( rsp );
                    break;
                case 0xC2:
                    tmp = Integer.parseInt(df.substring(0, 4), 16);
                    iv.par_w_angle = (tmp - 0x7fff) / 10;
                    break;
                case 0x161:
                    tmp = Integer.parseInt(df.substring(0, 2), 16);
                    iv.par_torque = tmp * 2 - 100;
                    break;
                case 0x1F9:
                    tmp = Integer.parseInt(df.substring(4, 8), 16);
                    iv.par_rpm = tmp / 80 * 10;
                    break;
                case 0x284:
                    tmp = Integer.parseInt(df.substring(0, 4), 16);
                    iv.par_speed_rw = tmp;
                    tmp = Integer.parseInt(df.substring(4, 8), 16);
                    iv.par_speed_lw = tmp;
                    tmp = Integer.parseInt(df.substring(8, 12), 16);
                    iv.par_speed = tmp / 100;
                    break;
                case 0x285:
                    tmp = Integer.parseInt(df.substring(0, 4), 16);
                    iv.par_speed_rrw = tmp;
                    tmp = Integer.parseInt(df.substring(4, 8), 16);
                    iv.par_speed_rlw = tmp;
                    iv.tireAssess();
                    break;
                case 0x354:
                    //tmp = Integer.parseInt(df.substring(0, 4), 16);
                    //iv.par_speed = tmp / 100;
                    break;
                case 0x551:
                    tmp = Integer.parseInt(df.substring(0, 2), 16);
                    iv.par_temp = tmp - 40;
                    tmp = Integer.parseInt(df.substring(2, 3), 16);
                    iv.consumptionAssess( tmp );
                    break;
            }
        } catch ( Exception e ) {
            if(D) Log.d(TAG, "parseResult error resp:" + rsp );
        }
    }

    private void initELM( ){
        sendCmd("at z");        // reset ELM
        sendCmd("at e1");       // echo on
        sendCmd("at l1");       // line feed
        sendCmd("at h1");       // show header (address DLC PCI)
        sendCmd("at d1");       // show DLC
        sendCmd("at sp6");      // choose CAN protocol
        sendCmd("at al");       // permit long frames
        sendCmd("at sh 7E0");   // set address for sending frames
        hErrorsPoling.post(errorPoller);
        //sendCmd("100210C0");        // open communication session
        //sendCmd("100317FF00");      // read errors STD-A
        //sendCmd("10031902AF");      // read errors STD-B
        //sendCmd("at ma");       // goto monitor mode
    }

}
