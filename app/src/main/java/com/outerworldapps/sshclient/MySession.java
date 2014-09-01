/**
 * Session management.
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


import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;

import com.jcraft.jsch.ChannelShell;

import java.io.IOException;
import java.io.OutputStreamWriter;

/**
 * A session screen consists of a box to enter username@hostname[:portnumber] in
 * and a box that displays screen data from the host.
 */
public class MySession extends LinearLayout {
    public static final String TAG = "SshClient";

    private boolean ctrlkeyflag;                  // when set, next key if 0x40..0x7F gets converted to 0x00..0x1F
    private ChannelShell currentconnection;       // current connection to remote host
    private HostNameText hostnametext;
    private int sessionNumber;
    private JschUserInfo jschuserinfo;            // callbacks to get stuff like passphrase and password from user
    private OutputStreamWriter currentoutput;     // stream to send data to remote host on
    private ScreenDataHandler screendatahandler;  // take events from screendatathread and process them in gui thread
    private ScreenDataThread screendatathread;    // connects to remote host then reads from remote host and posts to screendatahandler
    private ScreenTextView screentextview;        // holds the screen full of characters that came from the host
    private SshClient sshclient;

    public int portnumber;
    public String hostname;
    public String userhostport;
    public String username;

    public ChannelShell getCurrentconnection () { return currentconnection; }
    public HostNameText getHostnametext () { return hostnametext; }
    public JschUserInfo getJschuserinfo () { return jschuserinfo; }
    public ScreenDataHandler getScreendatahandler () { return screendatahandler; }
    public ScreenDataThread getScreendatathread () { return screendatathread; }
    public ScreenTextView getScreentextview () { return screentextview; }
    public SshClient getSshClient () { return sshclient; }

    public MySession (SshClient sc)
    {
        super (sc);

        sshclient = sc;
        sessionNumber = sc.getNextSessionNumber ();
        jschuserinfo = new JschUserInfo (this);
        screendatahandler = new ScreenDataHandler (this);

        setOrientation (LinearLayout.VERTICAL);

        hostnametext = new HostNameText (this);
        hostnametext.setLayoutParams (new LinearLayout.LayoutParams (
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.0F
        ));
        addView (hostnametext);

        screentextview = new ScreenTextView (this);
        screentextview.setLayoutParams (new LinearLayout.LayoutParams (
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0F
        ));
        addView (screentextview);
    }

    public String GetSessionName ()
    {
        return Integer.toString (sessionNumber) + " " + hostnametext.getText ().toString ();
    }

    /**
     * Display this session's screen and mark the session current.
     */
    public void ShowScreen ()
    {
        sshclient.setCurrentsession (this);

        // set the requested orientation from the settings value
        sshclient.setRequestedOrientation (sshclient.getSettings ().scr_orien.GetValue ());

        // display the screen
        sshclient.setContentView (this);

        // if currently connected, make sure the keyboard shows
        // seems sometimes Android refuses to do so on its own
        if (currentconnection != null) {
            screentextview.requestFocus ();
            InputMethodManager manager = (InputMethodManager)
                    sshclient.getSystemService (Context.INPUT_METHOD_SERVICE);
            manager.showSoftInput (screentextview, 0);
        }

        // otherwise if waiting for user to enter username@hostname[:portnumber] string,
        // put focus there
        if (hostnametext.GetState () == HostNameText.ST_ENTER) {
            hostnametext.requestFocus ();
        }
    }

    /**
     * Start connecting to an username@hostname[:portnumber].
     */
    public void StartConnecting ()
    {
        /*
         * Close down any old connection.
         */
        Disconnect ();

        /*
         * Parse the given supposed user@host[:port] string.
         */
        userhostport = hostnametext.getText ().toString ();
        try {
            int i = userhostport.indexOf ('@');
            if (i < 0) throw new Exception ("missing @ in " + userhostport);
            username   = userhostport.substring (0, i).trim ();
            hostname   = userhostport.substring (++ i).trim ();
            portnumber = 22;
            i = hostname.indexOf (':');
            if (i >= 0) {
                try {
                    portnumber = Integer.parseInt (hostname.substring (i + 1).trim ());
                } catch (NumberFormatException nfe) {
                    throw new Exception ("bad portnumber in " + userhostport);
                }
                hostname = hostname.substring (0, i).trim ();
            }
            userhostport = username + "@" + hostname +
                    ((portnumber == 22) ? "" : (":" + portnumber));
            hostnametext.setText (userhostport);
        } catch (Exception e) {
            sshclient.ErrorAlert ("Bad user@host[:port] string", e.getMessage ());
            return;
        }

        /*
         * Start a thread to initiate new connection then process incoming data.
         */
        screentextview.Reset ();
        Log.d (TAG, "starting connection thread");
        hostnametext.SetState (HostNameText.ST_CONN);
        screendatathread = new ScreenDataThread (this, sshclient);
        screendatathread.start ();
    }

    /**
     * Connection complete, set up connection and output.
     */
    public void SetConnectionOutput (ChannelShell con, OutputStreamWriter out)
    {
        currentconnection = con;
        currentoutput     = out;
    }

    /**
     * Disconnect from remote host.
     */
    public void Disconnect ()
    {
        hostnametext.SetState (HostNameText.ST_DISCO);
        if (currentconnection != null) {
            Log.d (TAG, "closing old connection");
            try { currentoutput.close (); } catch (Exception e) { }
            currentoutput = null;
            currentconnection.disconnect ();
            currentconnection = null;
        }
        if (screendatathread != null) {
            Log.d (TAG, "killing old thread");
            boolean exited = false;
            do {
                screendatathread.die = true;
                screentextview.ThawIt ();
                try {
                    screendatathread.join ();
                    exited = true;
                } catch (InterruptedException ie) {
                }
            } while (!exited);
            screendatathread  = null;
        }
    }

    /**
     * Display a message at the end of the screen.
     * @param msg = message to display
     */
    public void ScreenMsg (String msg)
    {
        screendatahandler.obtainMessage (ScreenDataHandler.SCREENMSG, msg).sendToTarget ();
    }

    /**
     * Next character that gets passed to SendCharToHost get ctrl-ified.
     */
    public void AssertCtrlKeyFlag ()
    {
        ctrlkeyflag = true;
    }

    /**
     * Send keyboard character(s) on to host for processing.
     * Don't send anything while squelched because the remote host might be blocked
     * and so the TCP link to the host is blocked and so we would block.
     */
    public void SendStringToHost (String txt)
    {
        if (screentextview.isFrozen ()) {
            Log.w (TAG, "send to host discarded while frozen");
        } else {
            OutputStreamWriter out = currentoutput;
            if (out != null) {
                try {
                    out.write (txt);
                    out.flush ();
                } catch (IOException ioe) {
                    Log.w (TAG, "transmit error", ioe);
                    hostnametext.SetState (HostNameText.ST_DISCO);
                    sshclient.ErrorAlert ("Transmit error", ioe.getMessage ());
                    try { out.close (); } catch (Exception e) { }
                    currentoutput = null;
                }
            }
        }
    }

    public void SendCharToHost (int code)
    {
        if (screentextview.isFrozen ()) {
            Log.w (TAG, "send to host discarded while frozen " + code);
            sshclient.MakeBeepSound ();
        } else {
            OutputStreamWriter out = currentoutput;
            if (out != null) {
                try {
                    if (ctrlkeyflag && (code >= 0x40) && (code <= 0x7F)) code &= 0x1F;
                    ctrlkeyflag = false;
                    out.write ((char)code);
                    out.flush ();
                } catch (IOException ioe) {
                    Log.w (TAG, "transmit error", ioe);
                    hostnametext.SetState (HostNameText.ST_DISCO);
                    sshclient.ErrorAlert ("Transmit error", ioe.getMessage ());
                    try { out.close (); } catch (Exception e) { }
                    currentoutput = null;
                }
            }
        }
    }
}
