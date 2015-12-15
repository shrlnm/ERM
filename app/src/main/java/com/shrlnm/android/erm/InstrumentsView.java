package com.shrlnm.android.erm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;

public class InstrumentsView extends View {

    // Debugging
    private static final String TAG = "ElmRenaultMonitor_Main";
    private static final boolean D = true;

    public Integer par_rpm      = 0;
    public Integer par_speed    = 0;
    public Integer par_speed_lw = 0;
    public Integer par_speed_rw = 0;
    public Integer par_speed_rlw= 0;
    public Integer par_speed_rrw= 0;
    public Integer par_torque   = 0;
    public Integer par_temp     = 0;
    public Integer par_w_angle  = 0;
    public Double  par_lph      = 0.0;
    public Double  par_consume  = 0.0;

    //tire assess
    public Double   ta_fl   = 0.0;
    public Double   ta_fr   = 0.0;
    public Double   ta_rl   = 0.0;
    public Double   ta_rr   = 0.0;

    //consumption assess
    public long     ca_prev_time  = 0;
    public int      ca_prev_start = 0;
    public int      ca_prev_value = 0;
    public int      ca_overflows  = 0;

    Canvas cnv;
    Paint paint;
    int         W;
    int         H;
    double      m;  //scale factor for font



    public InstrumentsView(Context context) {
        super(context);



        paint = new Paint();

        //load font
        //font = Typeface.createFromAsset(context.getAssets(), "HelveticaNeue.dfont");

    }
    private void text( int size, double x, double y, String str, Paint.Align align) {
        paint.setTextSize((float) (size * m));
        paint.setTextAlign(align);
        try {
            cnv.drawText(str, (float) (W * x), (float) (H * y), paint);
        } catch ( NullPointerException e ) {
            if(D) Log.e(TAG, "+++ NullPointer in draw +++");
        }
    }

    public void consumptionAssess( int newValue ) {

        long currentTime = System.nanoTime();

        if (ca_prev_time==0) {
            ca_prev_time = currentTime;
            ca_prev_start = newValue;
            return;
        }

        if (newValue<ca_prev_value) {
            ca_overflows = ca_overflows+1;

            par_lph = (256.0 - ca_prev_start + newValue) * 360000000.0 / (currentTime - ca_prev_time);
            par_consume = (ca_overflows * 256. + newValue)/10000;

            ca_prev_time = currentTime;
            ca_prev_start = newValue;
        }

        ca_prev_value = newValue;
    }

    public void tireAssess() {

        if (par_speed<1) return;

        double win = 10000.0;

        double mean_speed = ((par_speed_lw + par_speed_rw + par_speed_lw + par_speed_rrw)/4.);

        ta_fl = ((ta_fl * win)+(par_speed_lw -mean_speed)/mean_speed)/(win+1);
        ta_fr = ((ta_fr * win)+(par_speed_rw -mean_speed)/mean_speed)/(win+1);
        ta_rl = ((ta_rl * win)+(par_speed_rlw-mean_speed)/mean_speed)/(win+1);
        ta_rr = ((ta_rr * win)+(par_speed_rrw-mean_speed)/mean_speed)/(win+1);

    }

    public void onDraw( Canvas canva) {

        cnv = canva;

        W = cnv.getWidth();
        H = cnv.getHeight();
        m = 1.0;  //scale factor for font

        //fill background
        cnv.drawRGB(0, 0, 0);

        paint.setColor(Color.GREEN);
        //paint.setTypeface(font);
        //paint.setStyle(Paint.Style.FILL);

        cnv.drawLine((float) 0.27 * W, 0, (float) 0.27 * W, H, paint);
        cnv.drawLine((float) 0.77 * W, 0, (float) 0.77 * W, H, paint);

        if (par_speed > 70) paint.setColor(Color.RED);
        text(150, 0.6, 0.6, par_speed.toString(), Paint.Align.RIGHT);
        text(50, 0.6, 0.6, "km/h", Paint.Align.LEFT);
        paint.setColor(Color.GREEN);

        Integer tmp;
        try {
            text(80, 0.6, 0.2, par_rpm.toString(), Paint.Align.RIGHT);
            text(50, 0.6, 0.2, "rpm", Paint.Align.LEFT);

            tmp = par_speed_lw/200;
            text(50, 0.43, 0.7, tmp.toString(), Paint.Align.RIGHT);

            tmp = par_speed_rw/200;
            text(50, 0.59, 0.7, tmp.toString(), Paint.Align.RIGHT);

            text(80, 0.6, 0.95, par_torque.toString(), Paint.Align.RIGHT);
            text(50, 0.6, 0.95, "Nm", Paint.Align.LEFT);

            text(50, 0.89, 0.2, par_w_angle.toString(), Paint.Align.CENTER);
            text(30, 0.89, 0.1, "Wheel angle", Paint.Align.CENTER);

            text(30, 0.89, 0.5, "Tiers", Paint.Align.CENTER);
            text(30, 0.89, 0.6, String.format("%.3f", ta_fl*1000), Paint.Align.CENTER);
            text(30, 0.89, 0.7, String.format("%.3f", ta_fr*1000), Paint.Align.CENTER);
            text(30, 0.89, 0.8, String.format("%.3f", ta_rl*1000), Paint.Align.CENTER);
            text(30, 0.89, 0.9, String.format("%.3f", ta_rr*1000), Paint.Align.CENTER);

            text(50, 0.15, 0.20, par_temp.toString(), Paint.Align.RIGHT);
            text(30, 0.15, 0.20, "°C", Paint.Align.LEFT);

            text(50, 0.15, 0.35, String.format("%.1f", par_lph), Paint.Align.RIGHT);
            text(30, 0.15, 0.35, "l/h", Paint.Align.LEFT);

            text(50, 0.15, 0.50, String.format("%.1f", par_consume), Paint.Align.RIGHT);
            text(30, 0.15, 0.50, "l", Paint.Align.LEFT);
        } catch ( Exception e ) {
            if(D) Log.e(TAG, "+++ exception in draw +++");
        }
        invalidate();
    }
}
