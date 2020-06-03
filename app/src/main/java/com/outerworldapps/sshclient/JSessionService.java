/**
 * Service to hang on to SSH connections even if GUI put in background.
 * Accomplished by keeping a table of all ScreenDataThread objects of
 * which there is exactly one per open SSH connection.  It also maintains
 * a status bar notification indicating to the user that it is in memory
 * holding some SSH connections open (especially useful if app is being
 * used for tunnelling).
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.outerworldapps.sshclient;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.Collection;
import java.util.LinkedList;

public class JSessionService extends Service {
    public final static String TAG = "SshClient";

    private final static String APP_NAME = "SshClient";
    private final static String CHANNEL_ID = "sessioncount";

    private Context clientctx;
    private final MyBinder myBinder = new MyBinder ();
    private LinkedList<ScreenDataThread> screenDataThreads = new LinkedList<> ();
    private long startTime;
    private Notification notification;
    private ServiceConnection servconn;

    public int currentSessionNumber;

    /***************************\
     *  Service-context calls  *
    \***************************/

    @Override
    public void onCreate ()
    {
        Log.d (TAG, "JSessionService created");
        createNotificationChannel ();
    }

    @Override
    public void onDestroy ()
    {
        Log.d (TAG, "JSessionService destroyed with " + screenDataThreads.size () + " thread(s)");
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId)
    {
        Log.d (TAG, "JSessionService started");

        notification = createNotification (screenDataThreads.size ());
        if (notification != null) startForeground ((int)startTime ^ 1962078453, notification);

        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind (Intent intent)
    {
        return myBinder;
    }

    private class MyBinder extends Binder {
        JSessionService getService ()
        {
            return JSessionService.this;
        }
    }

    /****************************\
     *  Activity-context calls  *
    \****************************/

    public interface ConDiscon {
        void onConDiscon (JSessionService instance);
    }

    /**
     * Called to bind the service, instantiating the service.
     * Eventually gives a callback to callback.onConDiscon() with the instance when bound.
     */
    public static void bindit (final Context ctx, final ConDiscon callback)
    {
        ServiceConnection svc = new ServiceConnection () {
            private JSessionService jss;

            @Override
            public void onServiceConnected (ComponentName componentName, IBinder iBinder)
            {
                if (jss != null) throw new IllegalStateException ("already connected");
                MyBinder b = (MyBinder) iBinder;
                jss = b.getService ();
                jss.servconn  = this;
                jss.clientctx = ctx;
                Log.d (TAG, "JSessionService connected with " + jss.screenDataThreads.size () + " thread(s)");
                callback.onConDiscon (jss);
            }
            @Override
            public void onServiceDisconnected (ComponentName componentName)
            {
                if (jss == null) throw new IllegalStateException ("not connected");
                Log.d (TAG, "JSessionService disconnected with " + jss.screenDataThreads.size () + " thread(s)");
                jss.servconn  = null;
                jss.clientctx = null;
                jss = null;
                callback.onConDiscon (null);
            }
        };
        Intent i = new Intent (ctx, JSessionService.class);
        ctx.bindService (i, svc, BIND_AUTO_CREATE);
    }

    /**
     * Unbind the instance.
     * Eventually gives a callback to callback.onConDiscon() with null when unbound.
     */
    public void unbindit ()
    {
        if (clientctx != null) {
            clientctx.unbindService (servconn);
        }
    }

    /**
     * Create a screen data thread object (do not start it).
     * The thread exists as long as the connection is wanted by the user.
     */
    public ScreenDataThread createScreenDataThread ()
    {
        ScreenDataThread sdt = new ScreenDataThread ();
        screenDataThreads.addLast (sdt);
        updateNotificationConnectionCount ();
        return sdt;
    }

    /**
     * The screen data thread has been killed, ie, was no longer wanted by the user.
     */
    public void killedScreenDataThread (ScreenDataThread sdt)
    {
        screenDataThreads.remove (sdt);
        updateNotificationConnectionCount ();
    }

    /**
     * Return collection of all the threads we know about.
     */
    public Collection<ScreenDataThread> getAllScreenDataThreads ()
    {
        return screenDataThreads;
    }

    /**************\
     *  Internal  *
    \**************/

    /**
     * Update the status bar notification to indicate the current number of connections.
     */
    private void updateNotificationConnectionCount ()
    {
        int count = screenDataThreads.size ();

        if ((count > 0) && (startTime <= 0)) {
            startTime = System.currentTimeMillis ();
            Intent j = new Intent (clientctx, JSessionService.class);
            clientctx.startService (j);
        }

        if (notification != null) {
            notification = createNotification (count);
            if (notification != null) {
                NotificationManager nm = (NotificationManager) clientctx.getSystemService (Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify ((int)startTime ^ 1962078453, notification);
            }
        }

        if ((count <= 0) && (startTime > 0)) {
            startTime = 0;
            Intent j = new Intent (clientctx, JSessionService.class);
            clientctx.stopService (j);
            stopForeground (true);
        }
    }

    /**
     * Create a new status bar notification indicating the given connection count.
     */
    private Notification createNotification (int count)
    {
        if (clientctx != null) {
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new Notification.Builder (this, CHANNEL_ID);
            } else {
                nb = new Notification.Builder (this);
            }
            nb.setSmallIcon (R.drawable.launch_image);
            nb.setTicker (APP_NAME + " sessions");
            nb.setWhen (startTime);

            Intent ni = new Intent (clientctx, clientctx.getClass ());
            ni.setFlags (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity (clientctx, 0, ni, 0);

            nb.setContentTitle (APP_NAME + " sessions");
            nb.setContentText (count + " session" + (count == 1 ? "" : "s") + " open");
            nb.setContentIntent (pi);

            Notification note = nb.getNotification ();
            note.flags |= Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT |
                    Notification.FLAG_ONLY_ALERT_ONCE;
            return note;
        }
        return null;
    }

    // https://developer.android.com/training/notify-user/build-notification
    private void createNotificationChannel()
    {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = APP_NAME + " Sessions";
            String description = "number of " + APP_NAME + " sessions open";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel (CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
