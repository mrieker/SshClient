/**
 * Like AsyncTask except allows attach/detach of GUI thread context.
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

import android.os.Handler;
import android.os.Message;

public abstract class DetachableAsyncTask<Params,Progress,Result> implements Handler.Callback, Runnable {
    private final Object sendLock = new Object ();

    private boolean finished;           // thread finished
    private boolean progress;           // progress posted
    private Handler handler;            // handler while attached; null while detached
    private int sequence;               // number of times attached + detached
    private Params[] paramArray;        // parameters to pass to doInBackground()
    private Progress[] valuesArray;     // latest parameters to pass to onProgressUpdate()
    private Result result;              // result to pass to onPostExecute ()
    private Thread guiThread;           // GUI thread while attached; null while detached

    /**
     * Start executing the thread.
     */
    public final void execute (Params[] params)
    {
        onPreExecute ();            // setup in GUI thread
        paramArray = params;        // save parameters for doInBackground()
        new Thread (this).start (); // run doInBackground ()
    }

    /**
     * Tell thread it has a GUI.
     */
    public void attachGUI ()
    {
        synchronized (sendLock) {
            if (guiThread != null) throw new IllegalStateException ("already attached");
            guiThread = Thread.currentThread ();    // message handler assumes strict attach/detach cycle
            ++ sequence;                            // invalidate any messages in old handler's queue
            handler = new Handler (this);           // set up new handler
            if (finished | progress) {              // maybe we missed something while detached
                handler.sendEmptyMessage (sequence);
            }
        }
    }

    /**
     * Tell thread GUI is going away.
     */
    public void detachGUI ()
    {
        synchronized (sendLock) {
            if (Thread.currentThread () != guiThread) {
                throw new IllegalStateException ("not attached by calling thread");
            }
            guiThread = null;   // message handler assumes only attaching thread can detach
            ++ sequence;        // invalidate any messages in current handler's queue
            handler = null;     // throw current handler away
        }
    }

    /**
     * Implementation can override these.
     */
    protected void onPreExecute () { }
    protected abstract Result doInBackground (Params[] params);
    protected void onProgressUpdate (Progress[] values) { }
    protected void onPostExecute (Result result) { }

    /**
     * Background thread.
     */
    @Override  // Runnable
    public void run ()
    {
        try {
            result = doInBackground (paramArray);
        } finally {
            paramArray = null;
            synchronized (sendLock) {
                finished = true;
                if (handler != null) handler.sendEmptyMessage (sequence);
            }
        }
    }

    /**
     * doInBackground() calls this to publish progress which
     * calls onProgressUpdate() in GUI thread when attached.
     */
    @SuppressWarnings("SameParameterValue")
    protected final void publishProgress (Progress[] values)
    {
        synchronized (sendLock) {
            valuesArray = values;
            if (!progress) {
                progress = true;
                if (handler != null) handler.sendEmptyMessage (sequence);
            }
        }
    }

    /**
     * Called as part of Handler in GUI thread while attached.
     */
    @Override  // Handler.Callback
    public boolean handleMessage (Message msg)
    {
        boolean fin = false;
        boolean prg = false;
        Progress[] va = null;
        synchronized (sendLock) {           // make sure worker thread can't change anything
            if (msg.what == sequence) {     // make sure we are on current attached GUI thread
                fin = finished;             // see if worker thread has finished
                prg = progress;             // see if worker thread posted progress
                va  = valuesArray;          // see what the latest progress is
                finished = false;           // only process these calls once
                progress = false;
            }
        }
                                            // since only this thread can detach,
                                            // ... we can't be detached in here
                                            // and we can't be attached to a different thread
                                            // ... cuz it has to be detached to re-attach
        if (prg) onProgressUpdate (va);     // post latest progress update
        if (fin) onPostExecute (result);    // maybe worker is finished
        return true;
    }
}
