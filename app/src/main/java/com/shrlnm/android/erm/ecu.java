package com.shrlnm.android.erm;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class ecu {

    // Debugging
    private static final String TAG = "ERM_ecu_debug";
    private static final boolean D = true;

    //21a0   //can
    //7E8 10 1A 61 A0 CB 42 3F 13
    //7E8 21 05 0E 03 B7 03 B0 00
    //7E8 22 00 00 00 00 5A 14 00
    //7E8 23 06 00 00 00 00 00 00


    //0    0    1    1    2    2  2
    //01234567890123456789012345678
    //7E8 8 10 A0 02 03 04 05 06 07
    //7E8 10 A0 02 03 04 05 06 07
    //
    //0    0    1    1    2    2  2
    //01234567890123456789012345678
    //57 02 00 10 45 08 00 45
    //59 01 02 03 04 01 02 03 04

    private int         next_pci    = 0x10;
    private int         packetLen   = 0;
    private String      responce    = "";
    public  int         std         = 0;  //0 - unknown   1 - STD-A     2 - STD-B
    public List<String> errors;

    public ecu() {
        errors = new ArrayList<String>();
        next_pci   = 10;
        responce   = "";
    }

    public void parseErrors() {

        errors = new ArrayList<String>();

        int positive = Integer.parseInt(responce.substring(0,2), 16);

        if (positive == 0x57) {
            std = 1;
            int i = 6;
            while ( i+8 <= responce.length() ) {
                errors.add( responce.substring(i,i+8) );
                i += 9;
            }
        }

        if (positive == 0x59) {
            std = 2;
            int i = 3;
            while ( i+11 <= responce.length() ) {
                errors.add( responce.substring(i,i+11) );
                i += 12;
            }
        }

        if(D) Log.i(TAG, "parseErrors resp: " + responce + " : " + errors);
    }

    public void nextFrame( String frame ) {

        int off;

        // check if dlc is on
        if (frame.charAt(5)==' ') off = 2;
        else                      off = 0;

        //if (dlc_on) {
        //    int dlc = Integer.parseInt(frame.substring(4, 5), 16);
        //    if (frame.length()<(5+dlc*3)) return;
        //}

        int pci = Integer.parseInt(frame.substring(4+off, 6+off), 16);

        if (pci < 0x21) {
            next_pci = 0x21;
            packetLen = Integer.parseInt(frame.substring(7+off, 9+off), 16);
            responce = frame.substring(10+off, frame.length());
            return;
        } else if ( pci==next_pci ) {
            next_pci = next_pci + 1;
            responce += ' ' + frame.substring(7+off, frame.length());
            if (responce.length()==packetLen*3-1) parseErrors();
            responce = "";
            return;
        }

        next_pci = 0x10;
        packetLen = 0;
        responce  = "";
    }

}
